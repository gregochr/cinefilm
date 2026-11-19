package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import gregochr.cinefilm.credits.learn.CreditsEncoderBenchJob.EncoderFactory;
import gregochr.cinefilm.credits.learn.FeatureRunReader.FilmKeys;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run of the embeddings tool ({@code docs/engineering/credits-classifier-plan.md}, "{@code CreditsEmbedMain}"):
 * it embeds exactly the rows of a features run, in the run's order, and writes {@code embeddings.bin},
 * {@code embeddings.json} and, with {@code --look=L5}, {@code look.json} into
 * {@code <out>/embed-<yyyyMMdd'T'HHmmss'Z'>/} (made with {@code createDirectory}). No Spring, no database.
 *
 * <p><strong>Pass 1 opens no frame.</strong> It checks the summary, reads all of {@code features.csv} (a test row
 * without the look stops the run there), then each film's record, {@code complete.json} and every JPEG its rows name,
 * the dataset hash and the encoder. Any difference is refused: the dataset moved since the features run.
 *
 * <p><strong>Pass 2 encodes</strong>, single-threaded, in batches of up to {@value #BATCH} consecutive rows of one
 * kind of one film, to {@code embeddings.bin.partial}, renamed at the end. A failure deletes the run's directory.
 */
final class CreditsEmbedJob {

    /** The batch size, as in the product and the benchmark. */
    static final int BATCH = CreditsEncoderBenchJob.BATCH;

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final int READ_BYTES = 1 << 16;

    private final Clock clock;
    private final GitCommit gitCommit;
    private final EncoderFactory factory;
    private final BenchProbe probe;
    private final FrameSource source;

    /**
     * Creates the job.
     *
     * @param clock the clock that names the run and stamps {@code generatedAt}
     * @param gitCommit the commit of this build
     * @param factory opens the encoder; the seam that keeps the native library out of every test but one
     * @param probe the CPU clock and the library version
     * @param source everything read inside a film's directory
     */
    CreditsEmbedJob(Clock clock, GitCommit gitCommit, EncoderFactory factory, BenchProbe probe,
                    FrameSource source) {
        this.clock = clock;
        this.gitCommit = gitCommit;
        this.factory = factory;
        this.probe = probe;
        this.source = source;
    }

    /** What pass 1 settled: everything pass 2 and the outputs need. */
    private record Plan(List<FilmKeys> films, CreditsTrainJob.Hashes hashes, EncoderSpec spec,
                        String featuresCsvSha256, Path framesRoot) {
    }

    /** What pass 2 counted. */
    private record Counts(long devFilms, long testFilms, long devRows, long testRows, String devSha256) {
    }

    /**
     * Runs the tool.
     *
     * @param options the command line
     * @return what was written: the run directory, {@code embeddings.bin}, {@code embeddings.json} and, with
     *     {@code --look=L5}, {@code look.json}
     * @throws IOException when an input cannot be read or the output cannot be written
     * @throws IllegalArgumentException when pass 1 refuses an input or a frame is not the encoder's size (a refusal
     *     in pass 1 writes nothing; one in pass 2 deletes the run's directory)
     * @throws IllegalStateException when the encoder cannot be opened or fails
     */
    List<Path> run(CreditsEmbedOptions options) throws IOException {
        long cpuStart = probe.cpuNanos().getAsLong();
        Path data = options.data().toAbsolutePath().normalize();
        Path features = options.features().toAbsolutePath().normalize();
        Path encoderDir = options.encoder().toAbsolutePath().normalize();
        Path out = options.out().toAbsolutePath().normalize();
        Plan plan = check(data, features, encoderDir, out, options.look());

        // Opened before the run's directory exists, so a failure to open (even a native library that will not load)
        // leaves nothing to discard.
        try (ImageEncoder encoder = factory.open(plan.spec(), encoderDir)) {
            return write(plan, encoder, features, out, options.look(), cpuStart);
        }
    }

    private List<Path> write(Plan plan, ImageEncoder encoder, Path features, Path out, boolean look, long cpuStart)
            throws IOException {
        Instant now = clock.instant();
        Files.createDirectories(out);
        Path runDir = out.resolve("embed-" + STAMP.format(now));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        try {
            Path bin = runDir.resolve("embeddings.bin");
            Counts counts = embed(plan, encoder, runDir.resolve("embeddings.bin.partial"), bin);
            Path json = runDir.resolve("embeddings.json");
            Files.write(json, MAPPER.writeValueAsBytes(describe(plan, features, counts, now,
                (probe.cpuNanos().getAsLong() - cpuStart) / NANOS_PER_SECOND)));
            List<Path> written = new ArrayList<>(List.of(runDir, bin, json));
            if (look) {
                Path lookJson = runDir.resolve("look.json");
                Files.write(lookJson, MAPPER.writeValueAsBytes(new CreditsFeaturesJob.LookLog("L5", now.toString(),
                    gitCommit.commit(), (int) counts.testFilms())));
                written.add(lookJson);
            }
            return written;
        } catch (IOException | RuntimeException e) {
            discard(runDir, e);
            throw e;
        }
    }

    private Plan check(Path data, Path features, Path encoderDir, Path out, boolean look) throws IOException {
        if (!Files.isDirectory(data)) {
            throw new IllegalArgumentException("--data is not a directory: " + data);
        }
        if (!Files.isDirectory(features)) {
            throw new IllegalArgumentException("--features is not a directory: " + features);
        }
        Path realOut = DatasetMeasureJob.resolved(out);
        if (realOut.startsWith(data.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside --data: " + out);
        }
        if (realOut.startsWith(encoderDir.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside the encoder's directory: " + out);
        }
        CreditsTrainJob.Hashes summary = CreditsTrainJob.readSummary(features.resolve("summary.json"));
        if (look && !Files.isRegularFile(features.resolve("look.json"))) {
            throw new IllegalArgumentException("--look=L5 needs a look.json in the features run: " + features);
        }
        Path csv = features.resolve("features.csv");
        List<FilmKeys> films = FeatureRunReader.keys(csv, look);
        Map<Long, FrameLabelRecord> labels = FeatureRunReader.labels(features.resolve("frame-labels.json"));
        Path framesRoot = data.resolve("frames");
        MessageDigest dataset = digest();
        for (FilmKeys film : films) {
            checkFilm(film, labels.get(film.id()), framesRoot.resolve(Long.toString(film.id())), dataset);
        }
        String actual = hex(dataset.digest());
        if (!actual.equals(summary.datasetSha256())) {
            throw new IllegalArgumentException("the development films' complete.json files hash to " + actual
                + ", summary.json says " + summary.datasetSha256()
                + ": the dataset has changed since the features run");
        }
        EncoderSpec spec = EncoderSpec.read(encoderDir);
        return new Plan(films, summary, spec, sha256(csv),
            framesRoot);
    }

    private void checkFilm(FilmKeys film, FrameLabelRecord record, Path dir, MessageDigest dataset)
            throws IOException {
        long id = film.id();
        if (record == null) {
            throw new IllegalArgumentException("film " + id + " has no record in frame-labels.json");
        }
        byte[] bytes;
        try {
            bytes = source.completeBytes(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("film " + id + ": complete.json cannot be read: " + e.getMessage(), e);
        }
        DatasetComplete complete = DatasetComplete.parse(bytes).orElseThrow(
            () -> new IllegalArgumentException("film " + id + ": complete.json is not usable"));
        if (complete.mediaFileId() != id) {
            throw new IllegalArgumentException(
                "film " + id + ": complete.json is of media file " + complete.mediaFileId());
        }
        if (!Objects.equals(complete.fingerprint(), record.fingerprint())) {
            throw new IllegalArgumentException("film " + id + ": complete.json has fingerprint "
                + complete.fingerprint() + ", frame-labels.json says " + record.fingerprint());
        }
        requireJpegs(id, dir, FilmCheck.COARSE, film.coarseOffsets());
        requireJpegs(id, dir, FilmCheck.DENSE, film.denseOffsets());
        if (film.side() == Side.DEV) {
            dataset.update(bytes);
        }
    }

    private void requireJpegs(long id, Path dir, String kind, List<Long> offsets) {
        for (long offset : offsets) {
            if (!source.hasJpeg(dir, kind, offset)) {
                throw new IllegalArgumentException("film " + id + ": " + kind + "/" + offset + ".jpg is not a file");
            }
        }
    }

    private Counts embed(Plan plan, ImageEncoder encoder, Path partial, Path bin) throws IOException {
        EncoderSpec spec = plan.spec();
        MessageDigest dev = digest();
        long devFilms = 0;
        long testFilms = 0;
        long devRows = 0;
        long testRows = 0;
        try (EmbeddingFile.Writer writer = EmbeddingFile.create(partial, spec.dimension())) {
            for (FilmKeys film : plan.films()) {
                boolean development = film.side() == Side.DEV;
                Path dir = plan.framesRoot().resolve(Long.toString(film.id()));
                encode(encoder, writer, spec, film, dir, FilmCheck.COARSE, EmbeddingFile.COARSE, film.coarseOffsets(),
                    development ? dev : null);
                encode(encoder, writer, spec, film, dir, FilmCheck.DENSE, EmbeddingFile.DENSE, film.denseOffsets(),
                    development ? dev : null);
                long rows = film.coarseOffsets().size() + film.denseOffsets().size();
                devFilms += development ? 1 : 0;
                testFilms += development ? 0 : 1;
                devRows += development ? rows : 0;
                testRows += development ? 0 : rows;
            }
        }
        Files.move(partial, bin, StandardCopyOption.ATOMIC_MOVE);
        return new Counts(devFilms, testFilms, devRows, testRows, hex(dev.digest()));
    }

    /** Embeds one film's rows of one kind, {@value #BATCH} at a time, writing a record for each. */
    private void encode(ImageEncoder encoder, EmbeddingFile.Writer writer, EncoderSpec spec, FilmKeys film, Path dir,
                        String kind, int kindByte, List<Long> offsets, MessageDigest digest) throws IOException {
        for (int from = 0; from < offsets.size(); from += BATCH) {
            List<Long> batch = offsets.subList(from, Math.min(from + BATCH, offsets.size()));
            List<RgbFrame> frames = new ArrayList<>(batch.size());
            for (long offset : batch) {
                RgbFrame frame = FramePrep.decodeJpeg(source.jpeg(dir, kind, offset));
                if (frame.width() != spec.size() || frame.height() != spec.size()) {
                    throw new IllegalArgumentException("film " + film.id() + ": " + kind + "/" + offset + ".jpg is "
                        + frame.width() + "x" + frame.height() + ", the encoder takes " + spec.size() + "x"
                        + spec.size());
                }
                frames.add(frame);
            }
            float[][] vectors = encoder.encode(frames);
            for (int i = 0; i < batch.size(); i++) {
                writer.append(film.id(), kindByte, batch.get(i), vectors[i], digest);
            }
        }
    }

    private ObjectNode describe(Plan plan, Path features, Counts counts, Instant now, double cpuSeconds) {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("toolGitCommit", gitCommit.commit());
        json.put("generatedAt", now.toString());
        json.put("featuresRun", features.getFileName().toString());
        json.put("featuresCsvSha256", plan.featuresCsvSha256());
        json.put("featureVersion", CreditsFeatures.FEATURE_VERSION);
        json.put("datasetSha256", plan.hashes().datasetSha256());
        json.put("labelsSha256", plan.hashes().labelsSha256());
        ObjectNode encoder = json.putObject("encoder");
        encoder.put("name", plan.spec().name());
        encoder.put("sha256", plan.spec().sha256());
        encoder.put("dimension", plan.spec().dimension());
        json.put("onnxRuntimeVersion", probe.onnxVersion().get());
        ObjectNode films = json.putObject("films");
        films.put("dev", counts.devFilms());
        films.put("test", counts.testFilms());
        ObjectNode rows = json.putObject("rows");
        rows.put("dev", counts.devRows());
        rows.put("test", counts.testRows());
        json.put("devSha256", counts.devSha256());
        json.put("cpuSeconds", cpuSeconds);
        return json;
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
