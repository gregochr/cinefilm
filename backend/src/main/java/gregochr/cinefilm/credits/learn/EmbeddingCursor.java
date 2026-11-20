package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.learn.FeatureRunReader.FilmRows;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads an embeddings run's {@code embeddings.bin} in step with {@link FeatureRunReader#forEachFilm}
 * ({@code docs/engineering/credits-classifier-plan.md}, "{@code CreditsEvaluateMain} reads model B"). The embeddings
 * tool writes one record per data row of {@code features.csv}, in the file's order, which is {@code (mediaFileId,
 * kind, offsetMs)} with coarse before dense: so a film's rows are the next {@code coarse + dense} records. Test
 * films' rows exist in {@code features.csv}, and so in the records, only for a features run made with
 * {@code --look=L5}; without the look the features reader stops at the first test row and no record is read.
 *
 * <p>Only one film's embeddings is held at a time: each call to {@link #film} drops the film before it.
 */
final class EmbeddingCursor implements AutoCloseable {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int READ_BYTES = 1 << 16;

    /** One film's embeddings, one per row, in the rows' order. */
    record FilmEmbeddings(float[][] coarse, float[][] dense) {
    }

    /** What {@code embeddings.json} says that an evaluation checks. */
    private record Description(String featuresCsvSha256, String datasetSha256, String labelsSha256,
                               int featureVersion, String encoderSha256, int dimension, String devSha256) {
    }

    private final EmbeddingFile.Reader reader;
    private long consumed;
    private FilmEmbeddings held;

    private EmbeddingCursor(EmbeddingFile.Reader reader) {
        this.reader = reader;
    }

    /**
     * Checks an embeddings run against the features run and the training runs, and opens its records.
     *
     * @param dir the embeddings run
     * @param features the features run
     * @param hashes the features run's dataset and labels hashes
     * @param look whether test films are read, which needs the embeddings run to have been made with the look
     * @param runs the training runs, all model B
     * @return the cursor, before the first record; close it when done
     * @throws IOException when a file cannot be read
     * @throws IllegalArgumentException when the embeddings run is not what was made from this features run, was not
     *     made with the look when {@code look} is set, or does not hold the embeddings the training runs used
     */
    static EmbeddingCursor open(Path dir, Path features, CreditsTrainJob.Hashes hashes, boolean look,
                                List<TrainedRun> runs) throws IOException {
        return open(dir, features, hashes, look, runs, EmbeddingFile::open);
    }

    /** Opens {@code embeddings.bin}; the seam that lets a test see the reader a cursor closes. */
    interface Opener {
        EmbeddingFile.Reader open(Path file) throws IOException;
    }

    /** As {@link #open(Path, Path, CreditsTrainJob.Hashes, boolean, List)}, opening the records with {@code opener}. */
    static EmbeddingCursor open(Path dir, Path features, CreditsTrainJob.Hashes hashes, boolean look,
                                List<TrainedRun> runs, Opener opener) throws IOException {
        Description described = describe(MAPPER.readTree(Files.readAllBytes(dir.resolve("embeddings.json"))));
        check(described, hashes, sha256(features.resolve("features.csv")), runs);
        if (look && !Files.isRegularFile(dir.resolve("look.json"))) {
            throw new IllegalArgumentException("--look=L5 needs an embeddings run made with --look=L5: no look.json "
                + "in " + dir);
        }
        EmbeddingFile.Reader reader = opener.open(dir.resolve("embeddings.bin"));
        if (reader.dimension() != described.dimension()) {
            int found = reader.dimension();
            reader.close();
            throw new IllegalArgumentException("embeddings.bin has dimension " + found
                + ", embeddings.json's encoder says " + described.dimension());
        }
        return new EmbeddingCursor(reader);
    }

    private static Description describe(JsonNode root) {
        JsonNode encoder = root.get("encoder");
        if (encoder == null || !encoder.isObject()) {
            throw new IllegalArgumentException("embeddings.json has no object field encoder");
        }
        return new Description(text(root, "featuresCsvSha256"), text(root, "datasetSha256"),
            text(root, "labelsSha256"), whole(root, "featureVersion"), text(encoder, "sha256"),
            whole(encoder, "dimension"), text(root, "devSha256"));
    }

    private static String text(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isString()) {
            throw new IllegalArgumentException("embeddings.json has no text field " + name);
        }
        return value.stringValue();
    }

    private static int whole(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException("embeddings.json has no whole-number field " + name);
        }
        return value.intValue();
    }

    private static void check(Description described, CreditsTrainJob.Hashes hashes, String featuresSha,
                              List<TrainedRun> runs) {
        if (!described.featuresCsvSha256().equals(featuresSha)) {
            throw new IllegalArgumentException("embeddings.json's featuresCsvSha256 is "
                + described.featuresCsvSha256() + ", features.csv's SHA-256 is " + featuresSha);
        }
        if (!described.datasetSha256().equals(hashes.datasetSha256())) {
            throw new IllegalArgumentException("embeddings.json's datasetSha256 is " + described.datasetSha256()
                + ", summary.json's is " + hashes.datasetSha256());
        }
        if (!described.labelsSha256().equals(hashes.labelsSha256())) {
            throw new IllegalArgumentException("embeddings.json's labelsSha256 is " + described.labelsSha256()
                + ", summary.json's is " + hashes.labelsSha256());
        }
        if (described.featureVersion() != CreditsFeatures.FEATURE_VERSION) {
            throw new IllegalArgumentException("embeddings.json has feature version " + described.featureVersion()
                + ", the code has " + CreditsFeatures.FEATURE_VERSION);
        }
        for (TrainedRun run : runs) {
            if (!run.encoder().sha256().equals(described.encoderSha256())) {
                throw new IllegalArgumentException(run.name() + ": its encoder's sha256 is "
                    + run.encoder().sha256() + ", embeddings.json's is " + described.encoderSha256());
            }
            if (run.encoder().dimension() != described.dimension()) {
                throw new IllegalArgumentException(run.name() + ": its encoder's dimension is "
                    + run.encoder().dimension() + ", embeddings.json's is " + described.dimension());
            }
            if (!run.embeddingsDevSha256().equals(described.devSha256())) {
                throw new IllegalArgumentException(run.name() + ": it was trained on embeddings with devSha256 "
                    + run.embeddingsDevSha256() + ", embeddings.json's is " + described.devSha256());
            }
        }
    }

    /**
     * Reads the embeddings of the next film: its coarse rows' records, then its dense rows'. The film before it is
     * dropped.
     *
     * @param rows the film, the next one in {@code features.csv}
     * @return its embeddings, one per row
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when a record's key is not its row's, or the records run out
     */
    FilmEmbeddings film(FilmRows rows) throws IOException {
        held = null;
        float[][] coarse = take(rows.id(), EmbeddingFile.COARSE, rows.coarseOffsets());
        float[][] dense = take(rows.id(), EmbeddingFile.DENSE, rows.denseOffsets());
        held = new FilmEmbeddings(coarse, dense);
        return held;
    }

    /**
     * The number of embeddings the cursor holds: the last film's, and no earlier one.
     *
     * @return the count
     */
    int heldEmbeddings() {
        return held == null ? 0 : held.coarse().length + held.dense().length;
    }

    private float[][] take(long id, int kind, List<Long> offsets) throws IOException {
        float[][] vectors = new float[offsets.size()][];
        for (int i = 0; i < vectors.length; i++) {
            if (!reader.next()) {
                throw new IllegalArgumentException("embeddings.bin has " + reader.rows()
                    + " records, fewer than features.csv's data rows");
            }
            if (reader.mediaFileId() != id || reader.kind() != kind || reader.offsetMs() != offsets.get(i)) {
                throw new IllegalArgumentException("embeddings.bin record " + consumed + " is (mediaFileId "
                    + reader.mediaFileId() + ", kind " + reader.kind() + ", offsetMs " + reader.offsetMs()
                    + "), features.csv data row " + consumed + " is (mediaFileId " + id + ", kind " + kind
                    + ", offsetMs " + offsets.get(i) + ")");
            }
            consumed++;
            vectors[i] = reader.vector();
        }
        return vectors;
    }

    /**
     * Checks that every record was used, after the last film.
     *
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when the file holds more records than {@code features.csv} has data rows
     */
    void finish() throws IOException {
        if (reader.next()) {
            throw new IllegalArgumentException("embeddings.bin has " + reader.rows() + " records, features.csv has "
                + consumed + " data rows");
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[READ_BYTES];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
