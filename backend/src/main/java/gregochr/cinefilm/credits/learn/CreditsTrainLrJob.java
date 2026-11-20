package gregochr.cinefilm.credits.learn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run of model B's training tool ({@code docs/engineering/credits-classifier-plan.md}, "{@code CreditsTrainLrMain}
 * (L4b-5)"): it reads {@code features.csv} as {@link CreditsTrainJob} does, reads an embeddings run's
 * {@code embeddings.bin} in step with the rows, fits the five shipping, ten pair and ten triple logistic-regression
 * models with {@link FoldModels}, and writes {@code model.json}, {@code oof-models.json}, {@code oof-logloss.json}
 * and {@code nested-models.json} into {@code <out>/train-lr-<yyyyMMdd'T'HHmmss'Z'>/} (made with
 * {@code createDirectory}, so a run never overwrites an earlier one). Everything is checked and fitted before the
 * directory is made; a failure after that deletes it. No database, no network, never a Spring bean.
 *
 * <p><strong>Only development rows are read</strong>, exactly as {@link TrainRows} reads them. Embeddings are read
 * record {@code i} against data row {@code i}: every row's key, kept or dropped, must match, and the record count
 * must be the row count. Only the kept rows' embeddings are held, as {@code float[]}.
 */
final class CreditsTrainLrJob {

    /** The share of the maximum heap the held embeddings may take. */
    static final double MEMORY_SHARE = 0.7;

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final int READ_BYTES = 1 << 16;

    private final Clock clock;
    private final GitCommit gitCommit;
    private final Fitter fitter;
    private final LongSupplier maxMemory;

    CreditsTrainLrJob(Clock clock, GitCommit gitCommit) {
        this(clock, gitCommit, LogisticRegression::fit, () -> Runtime.getRuntime().maxMemory());
    }

    CreditsTrainLrJob(Clock clock, GitCommit gitCommit, Fitter fitter, LongSupplier maxMemory) {
        this.clock = clock;
        this.gitCommit = gitCommit;
        this.fitter = fitter;
        this.maxMemory = maxMemory;
    }

    /** Fits one model; the seam that lets a test see exactly which rows each model was given. */
    @FunctionalInterface
    interface Fitter {
        /**
         * Fits a model.
         *
         * @param rows the embeddings, one per row
         * @param labels one label per row, 1 for credits and 0 for film
         * @param params the settings
         * @return the model
         */
        LogisticRegression fit(float[][] rows, int[] labels, LrParams params);
    }

    /** What {@code embeddings.json} says that the run records or checks. */
    private record Embeddings(String featuresCsvSha256, String datasetSha256, String labelsSha256,
                              int featureVersion, String encoderName, String encoderSha256, int dimension,
                              String devSha256) {
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return what was written: the run directory, {@code model.json}, {@code oof-models.json},
     *     {@code oof-logloss.json} and {@code nested-models.json}
     * @throws IOException when an input cannot be read or the output cannot be written
     * @throws IllegalArgumentException when a file is not what a features run or an embeddings run writes, a hash or
     *     the feature version differs, a key or the record count differs, the embeddings would not fit in memory, a
     *     row is a test row or has no fold, a fold has no coarse row left or a model cannot be fitted (nothing is
     *     written)
     */
    List<Path> run(CreditsTrainLrOptions options) throws IOException {
        Path embeddingsDir = options.embeddings().toAbsolutePath().normalize();
        Path out = options.out().toAbsolutePath().normalize();
        if (DatasetMeasureJob.resolved(out).startsWith(embeddingsDir.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside the embeddings run: " + options.out());
        }
        CreditsTrainJob.Hashes hashes = CreditsTrainJob.readSummary(options.summary());
        String manifestSha = hex(digest().digest(Files.readAllBytes(options.manifest())));
        Embeddings described = readDescription(embeddingsDir.resolve("embeddings.json"));
        check(described, hashes, sha256(options.features()));
        TrainRows rows = TrainRows.read(options.features(), options.withDense(), options.litTextVariant());
        float[][] byIndex = readEmbeddings(embeddingsDir.resolve("embeddings.bin"), described, rows);
        FoldModels<LogisticRegression> models = FoldModels.build(rows.kept(),
            held -> fit(rows.kept(), byIndex, held, options.params()),
            (model, row) -> model.predict(byIndex[row.index()]));
        ObjectNode logLoss = models.logLoss();

        Files.createDirectories(out);
        Path runDir = out.resolve("train-lr-" + STAMP.format(clock.instant()));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        try {
            Path model = runDir.resolve("model.json");
            Files.write(model, MAPPER.writeValueAsBytes(modelJson(options, described, hashes, manifestSha,
                embeddingsDir.getFileName().toString(), models.shipping())));
            Path oofModels = runDir.resolve("oof-models.json");
            Files.write(oofModels, MAPPER.writeValueAsBytes(modelsJson(models.pairs())));
            Path oofLogLoss = runDir.resolve("oof-logloss.json");
            Files.write(oofLogLoss, MAPPER.writeValueAsBytes(logLoss));
            Path nestedModels = runDir.resolve("nested-models.json");
            Files.write(nestedModels, MAPPER.writeValueAsBytes(modelsJson(models.triples())));
            return List.of(runDir, model, oofModels, oofLogLoss, nestedModels);
        } catch (IOException | RuntimeException e) {
            discard(runDir, e);
            throw e;
        }
    }

    // ---- Reading ------------------------------------------------------------------------------------------------

    private static Embeddings readDescription(Path json) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readAllBytes(json));
        JsonNode encoder = root.get("encoder");
        if (encoder == null || !encoder.isObject()) {
            throw new IllegalArgumentException("embeddings.json has no object field encoder");
        }
        return new Embeddings(text(root, "featuresCsvSha256"), text(root, "datasetSha256"),
            text(root, "labelsSha256"), whole(root, "featureVersion"), text(encoder, "name"),
            text(encoder, "sha256"), whole(encoder, "dimension"), text(root, "devSha256"));
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

    /** Refuses an embeddings run that was not made from this features run. */
    private static void check(Embeddings described, CreditsTrainJob.Hashes hashes, String featuresSha) {
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
    }

    /**
     * Reads {@code embeddings.bin} in step with the rows and keeps the embeddings of the rows that train.
     *
     * @return one slot per data row, holding the embedding of a kept row and {@code null} for a dropped one
     */
    private float[][] readEmbeddings(Path bin, Embeddings described, TrainRows rows) throws IOException {
        long[] ids = rows.mediaFileIds();
        try (EmbeddingFile.Reader reader = EmbeddingFile.open(bin)) {
            if (reader.dimension() != described.dimension()) {
                throw new IllegalArgumentException("embeddings.bin has dimension " + reader.dimension()
                    + ", embeddings.json's encoder says " + described.dimension());
            }
            if (reader.rows() != ids.length) {
                throw new IllegalArgumentException("embeddings.bin has " + reader.rows()
                    + " records, features.csv has " + ids.length + " data rows");
            }
            long needed = (long) rows.kept().size() * reader.dimension() * Float.BYTES;
            long available = maxMemory.getAsLong();
            if (needed > MEMORY_SHARE * available) {
                throw new IllegalArgumentException("the " + rows.kept().size() + " kept rows need " + needed
                    + " bytes of embeddings (rows x dimension x 4), more than 70% of the " + available
                    + " bytes of maximum memory; run with a larger -Xmx");
            }
            float[][] byIndex = new float[ids.length][];
            List<TrainRows.Kept> kept = rows.kept();
            int next = 0;
            for (int i = 0; reader.next(); i++) {
                if (reader.mediaFileId() != ids[i] || reader.kind() != rows.kinds()[i]
                    || reader.offsetMs() != rows.offsetsMs()[i]) {
                    throw new IllegalArgumentException("embeddings.bin record " + i + " is (mediaFileId "
                        + reader.mediaFileId() + ", kind " + reader.kind() + ", offsetMs " + reader.offsetMs()
                        + "), features.csv data row " + i + " is (mediaFileId " + ids[i] + ", kind "
                        + rows.kinds()[i] + ", offsetMs " + rows.offsetsMs()[i] + ")");
                }
                if (next < kept.size() && kept.get(next).index() == i) {
                    byIndex[i] = reader.vector();
                    next++;
                }
            }
            return byIndex;
        }
    }

    // ---- Fitting ------------------------------------------------------------------------------------------------

    private LogisticRegression fit(List<TrainRows.Kept> rows, float[][] byIndex, Set<Integer> held, LrParams params) {
        List<TrainRows.Kept> train = rows.stream().filter(row -> !held.contains(row.fold())).toList();
        float[][] embeddings = new float[train.size()][];
        int[] labels = new int[train.size()];
        for (int i = 0; i < embeddings.length; i++) {
            embeddings[i] = byIndex[train.get(i).index()];
            labels[i] = train.get(i).label();
        }
        return fitter.fit(embeddings, labels, params);
    }

    // ---- Writing ------------------------------------------------------------------------------------------------

    /** The models as one object, each under its key ({@code "k,j"} or {@code "k,j,m"}), in the order given. */
    private static ObjectNode modelsJson(Map<String, LogisticRegression> models) {
        ObjectNode json = MAPPER.createObjectNode();
        models.forEach((key, value) -> json.set(key, MAPPER.readTree(value.toJson())));
        return json;
    }

    private ObjectNode modelJson(CreditsTrainLrOptions options, Embeddings described, CreditsTrainJob.Hashes hashes,
                                 String manifestSha, String embeddingsRun, List<LogisticRegression> shipping) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "logreg");
        root.put("featureVersion", CreditsFeatures.FEATURE_VERSION);
        ObjectNode encoder = root.putObject("encoder");
        encoder.put("name", described.encoderName());
        encoder.put("sha256", described.encoderSha256());
        encoder.put("dimension", described.dimension());
        LrParams params = options.params();
        ObjectNode p = root.putObject("params");
        p.put("l2", params.l2());
        p.put("maxIterations", params.maxIterations());
        p.put("tolerance", params.tolerance());
        root.put("withDense", options.withDense());
        root.put("litTextVariant", options.litTextVariant());
        ArrayNode models = root.putArray("models");
        for (LogisticRegression model : shipping) {
            models.add(MAPPER.readTree(model.toJson()));
        }
        root.put("toolGitCommit", gitCommit.commit());
        root.put("datasetSha256", hashes.datasetSha256());
        root.put("labelsSha256", hashes.labelsSha256());
        root.put("manifestSha256", manifestSha);
        root.put("embeddingsRun", embeddingsRun);
        root.put("embeddingsDevSha256", described.devSha256());
        return root;
    }

    /** Deletes the run's own directory after a failure; a failure to delete is added to the original. */
    private static void discard(Path runDir, Exception failure) {
        try (Stream<Path> files = Files.list(runDir)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
            Files.delete(runDir);
        } catch (IOException e) {
            failure.addSuppressed(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[READ_BYTES];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
