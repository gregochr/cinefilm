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

class CreditsTrainMainTest {

    private static final String RUN = "train-20240331T233000Z";
    private static final String[] FAST = {"--trees", "1", "--depth", "1", "--bins", "2", "--min-leaf", "1"};

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private CreditsTrainJob job;

    @BeforeEach
    void setUp() {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsTrainJob(TestData.CLOCK, git);
    }

    private int run(String[] args) {
        return CreditsTrainMain.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** Five folds of two coarse rows, one credits and one film, split by the mean. */
    private static String csv() {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            csv.append(TrainFixtures.row(k + 1, k, "coarse", 0, "credits", TrainFixtures.lit(k, 90, 0)));
            csv.append(TrainFixtures.row(k + 1, k, "coarse", 1, "film", TrainFixtures.lit(k + 10, 10, 0)));
        }
        return csv.toString();
    }

    private String[] args(Path[] inputs, String... extra) {
        List<String> all = new ArrayList<>(List.of(TrainFixtures.args(inputs, temp.resolve("out"))));
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv());

        int code = run(args(inputs, FAST));

        Path runDir = temp.resolve("out").toAbsolutePath().normalize().resolve(RUN);
        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(out()).isEqualTo("wrote " + runDir + "\nwrote " + runDir.resolve("model.json")
            + "\nwrote " + runDir.resolve("oof-models.json") + "\nwrote " + runDir.resolve("oof-logloss.json")
            + "\nwrote " + runDir.resolve("nested-models.json") + "\n");
    }

    @Test
    void mainReturnsNormallyOnSuccessAndWritesTheFiles() throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv());

        CreditsTrainMain.main(args(inputs, FAST));

        try (var runs = Files.list(temp.resolve("out"))) {
            Path runDir = runs.findFirst().orElseThrow();
            assertThat(runDir.getFileName().toString()).matches("train-\\d{8}T\\d{6}Z");
            assertThat(Files.readString(runDir.resolve("model.json"))).contains("\"toolGitCommit\"");
        }
    }

    @Test
    void lookIsAnUnknownFlagAndNothingIsWritten() throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv());

        int code = run(args(inputs, "--look=L5"));

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("train: IllegalArgumentException: unknown argument --look=L5\n");
        assertThat(out()).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aTestRowExitsOneWithAOneLineMessageAndWritesNothing() throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv() + FeatureRows.row(99, null, Side.TEST, "coarse", 0,
            "film", TrainFixtures.lit(1, 0)));

        int code = run(args(inputs, FAST));

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("train: IllegalArgumentException: features.csv line 12: a row that is not a "
            + "development row with a fold is refused; nothing was written\n");
        assertThat(out()).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void anUnreadableInputExitsOne() throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv());
        Files.delete(inputs[2]);

        int code = run(args(inputs, FAST));

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("train: NoSuchFileException: ");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aSummaryThatIsNotJsonExitsOne() throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv());
        Files.writeString(inputs[1], "not json");

        int code = run(args(inputs, FAST));

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("train: ");
        assertThat(err()).endsWith("\n").hasLineCount(1);
    }

    @Test
    void theExitCodeIsPassedOnlyWhenItIsNotZero() {
        List<Integer> exits = new ArrayList<>();

        CreditsTrainMain.exitOnFailure(0, exits::add);
        CreditsTrainMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }
}
