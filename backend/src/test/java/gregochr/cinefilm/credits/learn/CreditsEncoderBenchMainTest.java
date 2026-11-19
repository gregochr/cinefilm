package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreditsEncoderBenchMainTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private CreditsEncoderBenchJob job;

    @BeforeEach
    void setUp() {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsEncoderBenchJob(TestData.CLOCK, git, (spec, dir) -> new StubEncoder(3),
            new BenchProbe(() -> 1L, () -> 1L, () -> 1L, () -> "9.9.9"), temp.resolve("no-status"));
    }

    private int run(String... args) {
        return CreditsEncoderBenchMain.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() throws IOException {
        Path out = temp.resolve("out");

        int code = run("--encoder", TinyFixture.dir().toString(), "--out", out.toString(), "--frames", "2",
            "--warmup", "0");

        Path runDir = out.toAbsolutePath().normalize().resolve("bench-20240331T233000Z");
        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(outBytes.toString(StandardCharsets.UTF_8))
            .isEqualTo("wrote " + runDir + "\nwrote " + runDir.resolve("bench.json") + "\n");
        assertThat(Files.readString(runDir.resolve("bench.json"))).contains("\"frames\" : 2");
    }

    @Test
    void aBadFlagExitsOneWithAOneLineMessageAndWritesNothing() {
        int code = run("--encoder", TinyFixture.dir().toString(), "--out", temp.resolve("out").toString(),
            "--frames", "0");

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("bench: IllegalArgumentException: flag --frames must be 1 to 10000: 0\n");
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void anEncoderDescriptionThatIsRefusedExitsOne() throws IOException {
        Path bad = Files.createDirectory(temp.resolve("bad"));
        TinyFixture.withJson(bad, TinyFixture.json(TinyFixture.SHA256, "0".repeat(64)));

        int code = run("--encoder", bad.toString(), "--out", temp.resolve("out").toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("bench: IllegalArgumentException: tiny.onnx has SHA-256 ");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void anOutputInsideTheEncodersDirectoryExitsOne() throws IOException {
        Path encoderDir = TinyFixture.withJson(Files.createDirectory(temp.resolve("enc")),
            Files.readString(TinyFixture.dir().resolve("encoder.json")));

        int code = run("--encoder", encoderDir.toString(), "--out", encoderDir.resolve("o").toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("bench: IllegalArgumentException: --out must not be inside the encoder's "
            + "directory: ");
    }

    @Test
    void aSymlinkedOutputIntoTheEncodersDirectoryExitsOneAndWritesNothing() throws IOException {
        Path encoderDir = TinyFixture.withJson(Files.createDirectory(temp.resolve("enc")),
            Files.readString(TinyFixture.dir().resolve("encoder.json")));
        Path link = Files.createSymbolicLink(temp.resolve("link"), encoderDir);

        int code = run("--encoder", encoderDir.toString(), "--out", link.resolve("o").toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("bench: IllegalArgumentException: --out must not be inside the encoder's "
            + "directory: ");
        assertThat(encoderDir.resolve("o")).doesNotExist();
    }

    @Test
    void anEncoderThatFailsToOpenExitsOneWithOneLine() {
        CreditsEncoderBenchJob failing = new CreditsEncoderBenchJob(TestData.CLOCK, mock(GitCommit.class),
            (spec, dir) -> {
                throw new IllegalStateException("cannot open\ntiny.onnx");
            }, new BenchProbe(() -> 1L, () -> 1L, () -> 1L, () -> "9.9.9"), temp.resolve("no-status"));

        int code = CreditsEncoderBenchMain.run(new String[] {"--encoder", TinyFixture.dir().toString(), "--out",
            temp.resolve("out").toString()}, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), failing);

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("bench: IllegalStateException: cannot open tiny.onnx\n");
    }

    @Test
    void anUnreadableEncoderDirectoryExitsOneAsAnIoFailure() {
        int code = run("--encoder", temp.resolve("missing").toString(), "--out", temp.resolve("out").toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("bench: NoSuchFileException: ");
    }

    @Test
    void exitIsCalledOnlyForANonZeroCode() {
        List<Integer> exits = new ArrayList<>();

        CreditsEncoderBenchMain.exitOnFailure(0, exits::add);
        CreditsEncoderBenchMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void helpPrintsTheUsageAndExitsZeroWithoutRunningTheJob() {
        int code = run("--help");

        assertThat(code).isZero();
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).startsWith("usage: CreditsEncoderBenchMain --encoder");
        assertThat(err()).isEmpty();
    }

    @Test
    void helpAmongOtherArgumentsIsAnUnknownArgument() {
        int code = run("--help", "--out", "/o");

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("bench: IllegalArgumentException: unknown argument --help\n");
    }

    @Test
    void aSingleArgumentThatIsNotHelpIsParsedAsAFlag() {
        int code = run("--frames");

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("bench: IllegalArgumentException: flag --frames needs a value\n");
    }

    @Test
    void theRealJobRefusesABadEncoderDescriptionBeforeItTouchesTheNativeLibrary() throws IOException {
        Path bad = Files.createDirectory(temp.resolve("bad"));
        TinyFixture.withJson(bad, TinyFixture.json(TinyFixture.SHA256, "0".repeat(64)));

        int code = CreditsEncoderBenchMain.run(new String[] {"--encoder", bad.toString(), "--out",
            temp.resolve("out").toString()}, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), CreditsEncoderBenchMain.job());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("bench: IllegalArgumentException: tiny.onnx has SHA-256 ");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void mainWithHelpPrintsTheUsageLineToStandardOutAndDoesNotExit() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            CreditsEncoderBenchMain.main(new String[] {"--help"});
        } finally {
            System.setOut(original);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo(
            "usage: CreditsEncoderBenchMain --encoder <dir> --out <dir> [--frames 1-10000, default 520] "
                + "[--warmup 0-1000, default 16]" + System.lineSeparator());
    }
}
