package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import gregochr.cinefilm.credits.learn.EmbedFixtures.Film;
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

class CreditsEmbedMainTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private Path data;
    private Path features;
    private Path out;
    private CreditsEmbedJob job;

    @BeforeEach
    void setUp() throws IOException {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        List<Film> films = List.of(Film.dev(1, 2, 1));
        data = temp.resolve("data");
        EmbedFixtures.dataset(data, films);
        features = EmbedFixtures.featuresRun(temp.resolve("runs"), EmbedFixtures.FEATURES_RUN, data, films, false);
        out = temp.resolve("out");
        job = new CreditsEmbedJob(TestData.CLOCK, git, (spec, dir) -> new ColourMeanEncoder(),
            new BenchProbe(() -> 1L, () -> 1L, () -> 1L, () -> "9.9.9"), FrameSource.disk());
    }

    private String[] args(String... more) {
        List<String> all = new ArrayList<>(List.of("--data", data.toString(), "--features", features.toString(),
            "--encoder", TinyFixture.dir().toString(), "--out", out.toString()));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    private int run(CreditsEmbedJob toRun, String... args) {
        return CreditsEmbedMain.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), toRun);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() {
        int code = run(job, args());

        Path runDir = out.toAbsolutePath().normalize().resolve("embed-20240331T233000Z");
        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEqualTo("wrote " + runDir + "\nwrote "
            + runDir.resolve("embeddings.bin") + "\nwrote " + runDir.resolve("embeddings.json") + "\n");
    }

    @Test
    void aBadFlagExitsOneWithAOneLineMessageAndWritesNothing() {
        int code = run(job, args("--look=L4"));

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("embed: IllegalArgumentException: --look must be L5: --look=L4\n");
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(out).doesNotExist();
    }

    @Test
    void aFailureInTheEncoderExitsOneWithOneLine() {
        CreditsEmbedJob failing = new CreditsEmbedJob(TestData.CLOCK, mock(GitCommit.class), (spec, dir) -> {
            throw new IllegalStateException("cannot open\ntiny.onnx");
        }, new BenchProbe(() -> 1L, () -> 1L, () -> 1L, () -> "9.9.9"), FrameSource.disk());

        int code = run(failing, args());

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("embed: IllegalStateException: cannot open tiny.onnx\n");
    }

    @Test
    void exitIsCalledOnlyForANonZeroCode() {
        List<Integer> exits = new ArrayList<>();

        CreditsEmbedMain.exitOnFailure(0, exits::add);
        CreditsEmbedMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }

    @Test
    void helpPrintsTheUsageAndExitsZeroWithoutRunningTheJob() {
        int code = run(job, "--help");

        assertThat(code).isZero();
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEqualTo("usage: CreditsEmbedMain --data <dir> "
            + "--features <features run> --encoder <dir> --out <dir> [--look=L5]\n");
        assertThat(err()).isEmpty();
        assertThat(out).doesNotExist();
    }

    @Test
    void aSingleArgumentThatIsNotHelpIsParsedAsAFlag() {
        int code = run(job, "--data");

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("embed: IllegalArgumentException: flag --data needs a value\n");
    }

    @Test
    void theRealJobRefusesABadEncoderDescriptionBeforeItTouchesTheNativeLibrary() throws IOException {
        Path bad = Files.createDirectory(temp.resolve("bad"));
        TinyFixture.withJson(bad, TinyFixture.json(TinyFixture.SHA256, "0".repeat(64)));

        int code = run(CreditsEmbedMain.job(), "--data", data.toString(), "--features", features.toString(),
            "--encoder", bad.toString(), "--out", out.toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("embed: IllegalArgumentException: tiny.onnx has SHA-256 ");
        assertThat(out).doesNotExist();
    }

    @Test
    void mainWithHelpPrintsTheUsageLineToStandardOutAndDoesNotExit() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            CreditsEmbedMain.main(new String[] {"--help"});
        } finally {
            System.setOut(original);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo("usage: CreditsEmbedMain --data <dir> "
            + "--features <features run> --encoder <dir> --out <dir> [--look=L5]" + System.lineSeparator());
    }
}
