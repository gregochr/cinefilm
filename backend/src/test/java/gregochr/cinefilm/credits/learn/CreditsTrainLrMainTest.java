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

class CreditsTrainLrMainTest {

    private static final String RUN = "train-lr-20240331T233000Z";
    private static final String[] FAST = {"--max-iterations", "5"};

    @TempDir
    Path temp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private CreditsTrainLrJob job;

    @BeforeEach
    void setUp() {
        GitCommit git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
        job = new CreditsTrainLrJob(TestData.CLOCK, git);
    }

    private int run(String[] args) {
        return CreditsTrainLrMain.run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
            new PrintStream(errBytes, true, StandardCharsets.UTF_8), job);
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** Five folds of two coarse rows, one credits and one film. */
    private static String csv() {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < FoldModels.FOLDS; k++) {
            csv.append(TrainFixtures.row(k + 1, k, "coarse", 0, "credits", TrainFixtures.lit(k, 90, 0)));
            csv.append(TrainFixtures.row(k + 1, k, "coarse", 1, "film", TrainFixtures.lit(k + 10, 10, 0)));
        }
        return csv.toString();
    }

    private static float[] vector(int i) {
        return new float[] {i % 2 == 0 ? 1f + 0.1f * i : -1f - 0.1f * i, 0.5f};
    }

    /** The inputs and an embeddings run, as the command line. */
    private String[] args(String csv, String... extra) throws IOException {
        return args(csv, (i, key) -> key, extra);
    }

    private String[] args(String csv, LrFixtures.KeyEdit edit, String... extra) throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv);
        Path embeddings = LrFixtures.embeddingsRun(temp.resolve("embed-20240101T000000Z"), inputs[0], 2,
            CreditsTrainLrMainTest::vector, edit, 0);
        List<String> all = new ArrayList<>(List.of(TrainFixtures.args(inputs, temp.resolve("out"))));
        all.addAll(List.of("--embeddings", embeddings.toString()));
        all.addAll(List.of(extra));
        return all.toArray(String[]::new);
    }

    @Test
    void aRunExitsZeroAndPrintsEveryPathItWroteOnePerLine() throws IOException {
        int code = run(args(csv(), FAST));

        Path runDir = temp.resolve("out").toAbsolutePath().normalize().resolve(RUN);
        assertThat(code).isZero();
        assertThat(err()).isEmpty();
        assertThat(out()).isEqualTo("wrote " + runDir + "\nwrote " + runDir.resolve("model.json")
            + "\nwrote " + runDir.resolve("oof-models.json") + "\nwrote " + runDir.resolve("oof-logloss.json")
            + "\nwrote " + runDir.resolve("nested-models.json") + "\n");
    }

    @Test
    void mainReturnsNormallyOnSuccessAndWritesTheFiles() throws IOException {
        CreditsTrainLrMain.main(args(csv(), FAST));

        try (var runs = Files.list(temp.resolve("out"))) {
            Path runDir = runs.findFirst().orElseThrow();
            assertThat(runDir.getFileName().toString()).matches("train-lr-\\d{8}T\\d{6}Z");
            assertThat(Files.readString(runDir.resolve("model.json"))).contains("\"type\" : \"logreg\"");
        }
    }

    @Test
    void executeExitsWithTheCodeOfAFailedRunAndNotOfASuccessfulOne() throws IOException {
        List<Integer> exits = new ArrayList<>();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        CreditsTrainLrMain.execute(args(csv(), FAST), out, err, job, exits::add);
        assertThat(exits).isEmpty();
        CreditsTrainLrMain.execute(new String[] {"--look"}, out, err, job, exits::add);

        assertThat(exits).containsExactly(1);
        assertThat(err()).isEqualTo("train-lr: IllegalArgumentException: unknown argument --look\n");
    }

    @Test
    void lookIsAnUnknownFlagAndNothingIsWritten() throws IOException {
        int code = run(args(csv(), "--look=L5"));

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("train-lr: IllegalArgumentException: unknown argument --look=L5\n");
        assertThat(out()).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aTestRowExitsOneWithAOneLineMessageAndWritesNothing() throws IOException {
        int code = run(args(csv() + FeatureRows.row(99, null, Side.TEST, "coarse", 0, "film",
            TrainFixtures.lit(1, 0)), FAST));

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("train-lr: IllegalArgumentException: features.csv line 12: a row that is not a "
            + "development row with a fold is refused; nothing was written\n");
        assertThat(out()).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void anEmbeddingThatDoesNotMatchItsRowExitsOneAndWritesNothing() throws IOException {
        int code = run(args(csv(), (i, key) -> i == 3 ? new long[] {key[0], key[1], 77} : key, FAST));

        assertThat(code).isEqualTo(1);
        assertThat(err()).isEqualTo("train-lr: IllegalArgumentException: embeddings.bin record 3 is (mediaFileId 2, "
            + "kind 0, offsetMs 77), features.csv data row 3 is (mediaFileId 2, kind 0, offsetMs 1)\n");
        assertThat(out()).isEmpty();
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void anUnreadableInputExitsOne() throws IOException {
        String[] args = args(csv(), FAST);
        Files.delete(temp.resolve("split.json"));

        int code = run(args);

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("train-lr: NoSuchFileException: ");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aSummaryThatIsNotJsonExitsOne() throws IOException {
        String[] args = args(csv(), FAST);
        Files.writeString(temp.resolve("summary.json"), "not json");

        int code = run(args);

        assertThat(code).isEqualTo(1);
        assertThat(err()).startsWith("train-lr: ");
        assertThat(err()).endsWith("\n").hasLineCount(1);
    }

    @Test
    void theExitCodeIsPassedOnlyWhenItIsNotZero() {
        List<Integer> exits = new ArrayList<>();

        CreditsTrainLrMain.exitOnFailure(0, exits::add);
        CreditsTrainLrMain.exitOnFailure(1, exits::add);

        assertThat(exits).containsExactly(1);
    }
}
