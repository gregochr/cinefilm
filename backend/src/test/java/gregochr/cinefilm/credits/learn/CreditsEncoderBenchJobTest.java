package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.credits.RgbFrame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CreditsEncoderBenchJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RUN = "bench-20240331T233000Z";

    @TempDir
    Path temp;

    private GitCommit git;
    private Path out;
    private Path status;

    @BeforeEach
    void setUp() throws IOException {
        git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        out = temp.resolve("out");
        status = temp.resolve("status");
        Files.writeString(status, "VmHWM:\t  654321 kB\n");
    }

    /** A supplier that answers the given values one after another. */
    private static LongSupplier sequence(long... values) {
        int[] next = {0};
        return () -> values[next[0]++];
    }

    private static List<Integer> rgb(RgbFrame frame) {
        List<Integer> pixels = new ArrayList<>();
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                pixels.add(frame.rgb(x, y));
            }
        }
        return pixels;
    }

    private BenchProbe probe(LongSupplier cpu, LongSupplier wall) {
        return new BenchProbe(cpu, wall, () -> 536_870_912L, () -> "9.9.9");
    }

    private CreditsEncoderBenchJob job(StubEncoder encoder, BenchProbe probe) {
        return new CreditsEncoderBenchJob(TestData.CLOCK, git, (spec, dir) -> encoder, probe, status);
    }

    private CreditsEncoderBenchOptions options(int frames, int warmup) {
        return new CreditsEncoderBenchOptions(TinyFixture.dir(), out, frames, warmup);
    }

    /** Four wall readings (open start, open end, timed start, timed end) and two CPU readings (timed start, end). */
    private BenchProbe exactProbe() {
        return probe(sequence(5_000_000_000L, 5_800_000_000L), sequence(1_000_000_000L, 3_500_000_000L,
            10_000_000_000L, 11_600_000_000L));
    }

    @Test
    void theRunWritesExactlyTheFiguresTheClocksGave() throws IOException {
        StubEncoder encoder = new StubEncoder(3);

        List<Path> written = job(encoder, exactProbe()).run(options(8, 0));

        Path runDir = out.toAbsolutePath().normalize().resolve(RUN);
        assertThat(written).containsExactly(runDir, runDir.resolve("bench.json"));
        String expected = """
            {"toolGitCommit": "toolsha", "generatedAt": "2024-03-31T23:30:00Z",
             "encoder": {"name": "tiny", "sha256": "%s", "dimension": 3},
             "onnxRuntimeVersion": "9.9.9", "frames": 8, "warmup": 0, "batch": 16,
             "session": {"intraOpThreads": 1, "interOpThreads": 1, "executionMode": "sequential",
                         "cpuArena": false, "graphOptimisation": "default"},
             "openSeconds": 2.5, "cpuMsPerFrame": 100.0, "wallMsPerFrame": 200.0,
             "film520CpuSeconds": 52.0, "film520WallSeconds": 104.0, "costLimitSeconds": 120,
             "peakRssKb": 654321, "maxHeapBytes": 536870912}
            """.formatted(TinyFixture.SHA256);
        assertThat(MAPPER.readTree(Files.readString(runDir.resolve("bench.json"))))
            .isEqualTo(MAPPER.readTree(expected));
        assertThat(encoder.closed).isTrue();
    }

    @Test
    void theJsonKeepsItsKeysInTheOrderTheSpecListsThem() throws IOException {
        job(new StubEncoder(3), exactProbe()).run(options(8, 0));

        String json = Files.readString(out.resolve(RUN).resolve("bench.json"));
        assertThat(MAPPER.readTree(json).propertyNames()).containsExactly("toolGitCommit",
            "generatedAt", "encoder", "onnxRuntimeVersion", "frames", "warmup", "batch", "session", "openSeconds",
            "cpuMsPerFrame", "wallMsPerFrame", "film520CpuSeconds", "film520WallSeconds", "costLimitSeconds",
            "peakRssKb", "maxHeapBytes");
    }

    /** Both clocks advance one millisecond on every read, so a run of any length has enough readings. */
    private BenchProbe ticking() {
        AtomicLong cpu = new AtomicLong();
        AtomicLong wall = new AtomicLong();
        return probe(() -> cpu.addAndGet(1_000_000L), () -> wall.addAndGet(1_000_000L));
    }

    @Test
    void theWarmUpFramesRunFirstAndNoClockIsReadForThem() throws IOException {
        StubEncoder encoder = new StubEncoder(3);
        List<Integer> batchesSeenAtEachCpuReading = new ArrayList<>();
        AtomicLong wall = new AtomicLong();
        BenchProbe probe = probe(() -> {
            batchesSeenAtEachCpuReading.add(encoder.batches.size());
            return batchesSeenAtEachCpuReading.size() * 1_000_000_000L;
        }, () -> wall.addAndGet(1));

        job(encoder, probe).run(options(40, 20));

        // 20 warm-up frames are batches 16 and 4, then 40 counted frames are 16, 16 and 8. The CPU clock is read
        // before and after each counted batch and never during the warm-up.
        assertThat(batchesSeenAtEachCpuReading).containsExactly(2, 3, 3, 4, 4, 5);
        assertThat(encoder.batches).containsExactly(16, 4, 16, 16, 8);
        assertThat(MAPPER.readTree(Files.readString(out.resolve(RUN).resolve("bench.json"))).get("warmup").asInt())
            .isEqualTo(20);
    }

    @Test
    void makingTheNoiseIsNotTimedOnlyTheRoundTripAndTheEncoderAre() throws IOException {
        AtomicLong now = new AtomicLong();
        List<Long> seeds = new ArrayList<>();
        ImageEncoder threeMillisecondsAFrame = new ImageEncoder() {
            @Override
            public float[][] encode(List<RgbFrame> frames) {
                now.addAndGet(3_000_000L * frames.size());
                return new float[frames.size()][3];
            }

            @Override
            public void close() {
            }
        };
        CreditsEncoderBenchJob job = new CreditsEncoderBenchJob(TestData.CLOCK, git,
            (spec, dir) -> threeMillisecondsAFrame, probe(() -> 0L, now::get), status, seed -> {
                seeds.add(seed);
                return pixels -> now.addAndGet(1_000_000_000L);
            });

        job.run(options(20, 4));

        // 24 frames were made at a second each and the clock moved 24 s for them, yet only the 20 counted frames
        // at 3 ms each are in the figure: 60 ms over 20 frames.
        assertThat(seeds).containsExactly(20261119L);
        assertThat(MAPPER.readTree(Files.readString(out.resolve(RUN).resolve("bench.json")))
            .get("wallMsPerFrame").asDouble()).isEqualTo(3.0);
    }

    @Test
    void theLastBatchMayBeShorter() throws IOException {
        StubEncoder encoder = new StubEncoder(3);

        job(encoder, ticking()).run(options(17, 0));

        assertThat(encoder.batches).containsExactly(16, 1);
    }

    @Test
    void aWholeNumberOfBatchesHasNoShortOne() throws IOException {
        StubEncoder encoder = new StubEncoder(3);

        job(encoder, ticking()).run(options(32, 0));

        assertThat(encoder.batches).containsExactly(16, 16);
    }

    @Test
    void theFramesAreSquareRoundTrippedAndTheSameOnEveryRun() throws IOException {
        StubEncoder first = new StubEncoder(3);
        StubEncoder second = new StubEncoder(3);

        job(first, exactProbe()).run(options(3, 1));
        out = temp.resolve("out2");
        job(second, exactProbe()).run(options(3, 1));

        assertThat(first.frames).hasSize(4);
        for (int i = 0; i < 4; i++) {
            RgbFrame frame = first.frames.get(i);
            assertThat(frame.width()).isEqualTo(224);
            assertThat(frame.height()).isEqualTo(224);
            assertThat(rgb(frame)).isEqualTo(rgb(second.frames.get(i)));
        }
        assertThat(rgb(first.frames.get(0))).isNotEqualTo(rgb(first.frames.get(1)));
    }

    @Test
    void aMissingStatusFileMeansANullPeak() throws IOException {
        Files.delete(status);

        job(new StubEncoder(3), exactProbe()).run(options(8, 0));

        assertThat(MAPPER.readTree(Files.readString(out.resolve(RUN).resolve("bench.json"))).get("peakRssKb")
            .isNull()).isTrue();
    }

    @Test
    void aMissingOutputDirectoryIsMadeAndAnExistingRunIsNeverOverwritten() throws IOException {
        job(new StubEncoder(3), exactProbe()).run(options(1, 0));

        assertThatThrownBy(() -> job(new StubEncoder(3), exactProbe()).run(options(1, 0)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("this run's directory already exists: ");
        assertThat(Files.readString(out.resolve(RUN).resolve("bench.json"))).contains("\"frames\"");
    }

    @Test
    void anOutputDirectoryInsideTheEncodersIsRefusedAndNothingIsWritten() throws IOException {
        StubEncoder encoder = new StubEncoder(3);
        Path encoderDir = TinyFixture.withJson(Files.createDirectory(temp.resolve("enc")),
            Files.readString(TinyFixture.dir().resolve("encoder.json")));
        CreditsEncoderBenchOptions inside = new CreditsEncoderBenchOptions(encoderDir, encoderDir.resolve("results"),
            1, 0);

        assertThatThrownBy(() -> job(encoder, exactProbe()).run(inside))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("--out must not be inside the encoder's directory: ");
        assertThat(encoderDir.resolve("results")).doesNotExist();
        assertThat(encoder.batches).isEmpty();
    }

    @Test
    void theEncoderDirectoryItselfAsTheOutputIsRefusedToo() {
        CreditsEncoderBenchOptions same = new CreditsEncoderBenchOptions(TinyFixture.dir(), TinyFixture.dir(), 1, 0);

        assertThatThrownBy(() -> job(new StubEncoder(3), exactProbe()).run(same))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSiblingWhoseNameStartsLikeTheEncodersDirectoryIsNotInsideIt() throws IOException {
        Path encoderDir = Files.createDirectory(temp.resolve("enc"));
        TinyFixture.withJson(encoderDir, Files.readString(TinyFixture.dir().resolve("encoder.json")));
        Path sibling = temp.resolve("enc-results");

        new CreditsEncoderBenchJob(TestData.CLOCK, git, (spec, dir) -> new StubEncoder(3), exactProbe(), status)
            .run(new CreditsEncoderBenchOptions(encoderDir, sibling, 1, 0));

        assertThat(sibling.resolve(RUN).resolve("bench.json")).exists();
    }

    @Test
    void anEncoderDescriptionThatIsRefusedStopsTheRunBeforeAnythingIsOpenedOrWritten() throws IOException {
        Path bad = Files.createDirectory(temp.resolve("bad"));
        TinyFixture.withJson(bad, TinyFixture.json(TinyFixture.SHA256, "0".repeat(64)));
        StubEncoder encoder = new StubEncoder(3);

        assertThatThrownBy(() -> job(encoder, exactProbe()).run(new CreditsEncoderBenchOptions(bad, out, 1, 0)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("has SHA-256");
        assertThat(out).doesNotExist();
        assertThat(encoder.closed).isFalse();
    }

    @Test
    void anEncoderThatFailsToOpenStopsTheRunAndWritesNothing() {
        CreditsEncoderBenchJob job = new CreditsEncoderBenchJob(TestData.CLOCK, git, (spec, dir) -> {
            throw new IllegalStateException("cannot open tiny.onnx: no");
        }, exactProbe(), status);

        assertThatThrownBy(() -> job.run(options(1, 0))).isInstanceOf(IllegalStateException.class)
            .hasMessage("cannot open tiny.onnx: no");
        assertThat(out).doesNotExist();
    }

    @Test
    void anEncoderThatFailsWhileRunningIsClosedAndWritesNothing() {
        StubEncoder encoder = new StubEncoder(3, new IllegalStateException("the output holds a NaN or infinite value"));

        assertThatThrownBy(() -> job(encoder, exactProbe()).run(options(1, 0)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("NaN");
        assertThat(encoder.closed).isTrue();
        assertThat(out).doesNotExist();
    }

    /** CI only: the real ONNX encoder cannot load on the Intel Mac (no osx-x64 natives). */
    @Test
    @EnabledOnOs(OS.LINUX)
    void oneRunEndToEndOnTheTinyFixtureWithTheRealEncoderWritesBenchJson() throws IOException {
        CreditsEncoderBenchJob real = new CreditsEncoderBenchJob(TestData.CLOCK, git, OnnxImageEncoder::new,
            BenchProbe.system(), Path.of("/proc/self/status"));

        List<Path> written = real.run(options(20, 4));

        var json = MAPPER.readTree(Files.readString(written.get(1)));
        assertThat(json.get("encoder").get("name").asString()).isEqualTo("tiny");
        assertThat(json.get("frames").asInt()).isEqualTo(20);
        assertThat(json.get("warmup").asInt()).isEqualTo(4);
        assertThat(json.get("onnxRuntimeVersion").asString()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(json.get("cpuMsPerFrame").asDouble()).isPositive();
        assertThat(json.get("wallMsPerFrame").asDouble()).isPositive();
        assertThat(json.get("openSeconds").asDouble()).isPositive();
        assertThat(json.get("peakRssKb").asLong()).isPositive();
        assertThat(json.get("maxHeapBytes").asLong()).isEqualTo(Runtime.getRuntime().maxMemory());
    }
}
