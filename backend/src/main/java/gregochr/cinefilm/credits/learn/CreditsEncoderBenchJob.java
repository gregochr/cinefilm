package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One run of the encoder benchmark ({@code docs/engineering/credits-classifier-plan.md}, "The benchmark"): it times an
 * encoder on synthetic frames and writes {@code bench.json} into {@code <out>/bench-<yyyyMMdd'T'HHmmss'Z'>/} (made
 * with {@code createDirectory}). It reads no dataset and no labels. Every frame is 224 x 224 RGB noise from a
 * {@link SplittableRandom} with a fixed seed, goes through the product's round trip ({@link FramePrep#jpeg} then
 * {@link FramePrep#decodeJpeg}), then {@link EncoderInput} and the encoder, in batches of {@value #BATCH}.
 *
 * <p><strong>What is timed.</strong> Each batch's noise is made first, outside the clocks, so memory holds one batch
 * of raw frames whatever {@code --frames} is. The CPU and wall clocks are then read just before and just after the
 * round trip and the encoder, and each batch's difference is added up. The warm-up frames are made and run the same
 * way but no clock is read for them. Everything is refused before anything is written. No Spring, no database.
 */
final class CreditsEncoderBenchJob {

    /** The batch size, as in the product. */
    static final int BATCH = 16;
    /** The seed of the synthetic frames. */
    static final long SEED = 20261119L;
    /** The number of frames in one film, which the per-film figures scale to. */
    static final int FILM_FRAMES = 520;
    /** The cost limit in seconds a film: a candidate that needs more is rejected. */
    static final int COST_LIMIT_SECONDS = 120;

    private static final double NANOS_PER_MS = 1_000_000.0;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private final Clock clock;
    private final GitCommit gitCommit;
    private final EncoderFactory factory;
    private final BenchProbe probe;
    private final Path statusFile;
    private final LongFunction<Consumer<byte[]>> noise;

    /**
     * Creates the job, with the noise a {@link SplittableRandom} seeded with {@link #SEED} makes.
     *
     * @param clock the clock that names the run and stamps {@code generatedAt}
     * @param gitCommit the commit of this build
     * @param factory opens the encoder; the seam that keeps the native library out of every test but one
     * @param probe the CPU, wall and heap readings and the library version
     * @param statusFile where {@code VmHWM} is read, normally {@code /proc/self/status}
     */
    CreditsEncoderBenchJob(Clock clock, GitCommit gitCommit, EncoderFactory factory, BenchProbe probe,
                           Path statusFile) {
        this(clock, gitCommit, factory, probe, statusFile, seed -> new SplittableRandom(seed)::nextBytes);
    }

    /**
     * Creates the job.
     *
     * @param clock the clock that names the run and stamps {@code generatedAt}
     * @param gitCommit the commit of this build
     * @param factory opens the encoder; the seam that keeps the native library out of every test but one
     * @param probe the CPU, wall and heap readings and the library version
     * @param statusFile where {@code VmHWM} is read, normally {@code /proc/self/status}
     * @param noise makes the pixel source from the seed; the seam that lets a test see when noise is made
     */
    CreditsEncoderBenchJob(Clock clock, GitCommit gitCommit, EncoderFactory factory, BenchProbe probe,
                           Path statusFile, LongFunction<Consumer<byte[]>> noise) {
        this.noise = noise;
        this.clock = clock;
        this.gitCommit = gitCommit;
        this.factory = factory;
        this.probe = probe;
        this.statusFile = statusFile;
    }

    /** Opens an encoder. */
    @FunctionalInterface
    interface EncoderFactory {
        /**
         * Opens the encoder.
         *
         * @param spec the encoder's description
         * @param dir the directory of its ONNX file
         * @return the open encoder
         */
        ImageEncoder open(EncoderSpec spec, Path dir);
    }

    /**
     * Runs the benchmark.
     *
     * @param options the command line
     * @return what was written: the run directory and {@code bench.json}
     * @throws IOException when an input cannot be read or the output cannot be written
     * @throws IllegalArgumentException when {@code --out} is inside the encoder's directory or the encoder's
     *     description is refused (nothing is written)
     * @throws IllegalStateException when the encoder cannot be opened or fails
     */
    List<Path> run(CreditsEncoderBenchOptions options) throws IOException {
        Path dir = options.encoder().toAbsolutePath().normalize();
        Path out = options.out().toAbsolutePath().normalize();
        if (DatasetMeasureJob.resolved(out).startsWith(dir.toRealPath())) {
            throw new IllegalArgumentException("--out must not be inside the encoder's directory: " + out);
        }
        EncoderSpec spec = EncoderSpec.read(dir);
        long openStart = probe.wallNanos().getAsLong();
        double openSeconds;
        long[] timed;
        try (ImageEncoder encoder = factory.open(spec, dir)) {
            openSeconds = (probe.wallNanos().getAsLong() - openStart) / NANOS_PER_SECOND;
            Consumer<byte[]> source = noise.apply(SEED);
            batches(encoder, spec, source, options.warmup(), false);
            timed = batches(encoder, spec, source, options.frames(), true);
        }
        double cpuNanos = timed[0];
        double wallNanos = timed[1];
        OptionalLong peakRssKb = PeakRss.read(statusFile);

        Files.createDirectories(out);
        Instant now = clock.instant();
        Path runDir = out.resolve("bench-" + STAMP.format(now));
        try {
            Files.createDirectory(runDir);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalArgumentException("this run's directory already exists: " + runDir, e);
        }
        double cpuMsPerFrame = cpuNanos / NANOS_PER_MS / options.frames();
        double wallMsPerFrame = wallNanos / NANOS_PER_MS / options.frames();
        ObjectNode json = MAPPER.createObjectNode();
        json.put("toolGitCommit", gitCommit.commit());
        json.put("generatedAt", now.toString());
        ObjectNode encoderJson = json.putObject("encoder");
        encoderJson.put("name", spec.name());
        encoderJson.put("sha256", spec.sha256());
        encoderJson.put("dimension", spec.dimension());
        json.put("onnxRuntimeVersion", probe.onnxVersion().get());
        json.put("frames", options.frames());
        json.put("warmup", options.warmup());
        json.put("batch", BATCH);
        ObjectNode session = json.putObject("session");
        session.put("intraOpThreads", 1);
        session.put("interOpThreads", 1);
        session.put("executionMode", "sequential");
        session.put("cpuArena", false);
        session.put("graphOptimisation", "default");
        json.put("openSeconds", openSeconds);
        json.put("cpuMsPerFrame", cpuMsPerFrame);
        json.put("wallMsPerFrame", wallMsPerFrame);
        json.put("film520CpuSeconds", cpuMsPerFrame * FILM_FRAMES / 1000.0);
        json.put("film520WallSeconds", wallMsPerFrame * FILM_FRAMES / 1000.0);
        json.put("costLimitSeconds", COST_LIMIT_SECONDS);
        if (peakRssKb.isPresent()) {
            json.put("peakRssKb", peakRssKb.getAsLong());
        } else {
            json.putNull("peakRssKb");
        }
        json.put("maxHeapBytes", probe.maxHeapBytes().getAsLong());
        Path bench = runDir.resolve("bench.json");
        Files.write(bench, MAPPER.writeValueAsBytes(json));
        return List.of(runDir, bench);
    }

    /**
     * Runs {@code count} synthetic frames through the round trip and the encoder, {@value #BATCH} at a time. A
     * batch's noise is made before its clocks are read.
     *
     * @return the CPU and wall nanoseconds of the round trips and encoder calls, both 0 when not {@code timed}
     */
    private long[] batches(ImageEncoder encoder, EncoderSpec spec, Consumer<byte[]> source, int count,
                           boolean timed) {
        long cpu = 0;
        long wall = 0;
        for (int done = 0; done < count; done += BATCH) {
            int size = Math.min(BATCH, count - done);
            List<RgbFrame> raw = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                byte[] pixels = new byte[spec.size() * spec.size() * 3];
                source.accept(pixels);
                raw.add(new RgbFrame(spec.size(), spec.size(), pixels));
            }
            long cpuStart = timed ? probe.cpuNanos().getAsLong() : 0;
            long wallStart = timed ? probe.wallNanos().getAsLong() : 0;
            List<RgbFrame> batch = new ArrayList<>(size);
            for (RgbFrame frame : raw) {
                batch.add(FramePrep.decodeJpeg(FramePrep.jpeg(frame)));
            }
            encoder.encode(batch);
            if (timed) {
                cpu += probe.cpuNanos().getAsLong() - cpuStart;
                wall += probe.wallNanos().getAsLong() - wallStart;
            }
        }
        return new long[] {cpu, wall};
    }
}
