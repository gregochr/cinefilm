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

class CreditsFeaturesMainTest {

    private static final String RUN = "features-20240331T233000Z";

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private Path data;
    private Path frames;
    private Path labels;
    private CreditsFeaturesJob job;

    @BeforeEach
    void setUp() throws IOException {
        data = Files.createDirectories(temp.resolve("data"));
        frames = Files.createDirectories(data.resolve("frames"));
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsFeaturesJob(TestData.CLOCK, git,
            FilmSource.disk());
        labels = MeasureFixtures.labelsFile(temp.resolve("labels.json"),
            FeaturesFixtures.label(1, FeaturesFixtures.FINGERPRINT));
    }

    private int run(String... given) {
        List<String> all = new ArrayList<>(List.of(given));
        if (!all.contains("--labels")) {
            all.addAll(List.of("--labels", labels.toString()));
        }
        return CreditsFeaturesMain.run(all.toArray(String[]::new),
            new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() throws IOException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        Path out = temp.resolve("out");

        int code = run("--data", data.toString(), "--out", out.toString());

        Path runDir = out.toAbsolutePath().normalize().resolve(RUN);
        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(out()).isEqualTo("wrote " + runDir + "\nwrote " + runDir.resolve("features.csv")
            + "\nwrote " + runDir.resolve("frame-labels.json") + "\nwrote " + runDir.resolve("summary.json") + "\n");
    }

    @Test
    void mainReturnsNormallyOnSuccessAndWritesTheFiles() throws IOException {
        FeaturesFixtures.okFilm(frames, "1", 1);
        Path out = temp.resolve("out");

        CreditsFeaturesMain.main(new String[] {"--data", data.toString(), "--out", out.toString(), "--labels",
            labels.toString()});

        try (var runs = Files.list(out)) {
            Path runDir = runs.findFirst().orElseThrow();
            assertThat(runDir.getFileName().toString()).matches("features-\\d{8}T\\d{6}Z");
            assertThat(Files.readString(runDir.resolve("summary.json"))).contains("\"toolGitCommit\"");
        }
    }

    @Test
    void aBadFlagExitsOneWithAOneLineMessage() {
        int code = run("--data", data.toString(), "--look=L5", "--out", temp.resolve("out").toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("features: IllegalArgumentException: unknown argument --look=L5\n");
        assertThat(out()).isEmpty();
    }

    @Test
    void anUnreadableLabelsFileExitsOne() {
        int code = run("--data", data.toString(), "--out", temp.resolve("out").toString(), "--labels",
            temp.resolve("missing.json").toString());

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("features: NoSuchFileException: ");
    }

    @Test
    void aDataDirectoryWithNoFilmsExitsTwoAndWritesNothing() {
        Path out = temp.resolve("out");

        int code = run("--data", data.toString(), "--out", out.toString());

        assertThat(code).isEqualTo(2);
        assertThat(err()).isEqualTo("features: no film directories under " + frames.toAbsolutePath().normalize()
            + "\n");
        assertThat(out).doesNotExist();
    }

    @Test
    void theExitCodeIsPassedOnlyWhenItIsNotZero() {
        List<Integer> exits = new ArrayList<>();

        CreditsFeaturesMain.exitOnFailure(0, exits::add);
        CreditsFeaturesMain.exitOnFailure(2, exits::add);

        assertThat(exits).containsExactly(2);
    }
}
