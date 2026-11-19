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

class CreditsEvaluateMainTest {

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private CreditsEvaluateJob job;
    private String[] args;

    @BeforeEach
    void setUp() throws IOException {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsEvaluateJob(TestData.CLOCK, git);
        args = new String[] {"--features", EvaluateFixtures.featuresRun(temp, false).toString(), "--out",
            temp.resolve("out").toString(), "--train", EvaluateFixtures.trainRun(temp, "train-a").toString()};
    }

    private int run(String... arguments) {
        return CreditsEvaluateMain.run(arguments, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() {
        int code = run(args);

        Path dir = temp.resolve("out").toAbsolutePath().normalize().resolve("eval-20240331T233000Z");
        assertThat(code).isZero();
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEqualTo("wrote " + dir + "\nwrote "
            + dir.resolve("evaluation.csv") + "\nwrote " + dir.resolve("calibration.json") + "\nwrote "
            + dir.resolve("summary.json") + "\n");
    }

    @Test
    void aRefusalExitsOneWithAOneLineMessageAndWritesNothing() {
        List<String> look = new ArrayList<>(List.of(args));
        look.add("--look=L5");

        int code = run(look.toArray(String[]::new));

        assertThat(code).isEqualTo(1);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
            .startsWith("evaluate: IllegalArgumentException: --look=L5 needs a features run made with --look=L5: "
                + "no look.json in ").hasLineCount(1);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void mainReturnsNormallyOnSuccessAndWritesTheFiles() throws IOException {
        CreditsEvaluateMain.main(args);

        try (var runs = Files.list(temp.resolve("out"))) {
            assertThat(runs.findFirst().orElseThrow().getFileName().toString()).matches("eval-\\d{8}T\\d{6}Z");
        }
    }

    @Test
    void theExitCodeIsPassedOnlyWhenItIsNotZero() {
        List<Integer> exits = new ArrayList<>();

        CreditsEvaluateMain.exitOnFailure(0, exits::add);
        CreditsEvaluateMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }
}
