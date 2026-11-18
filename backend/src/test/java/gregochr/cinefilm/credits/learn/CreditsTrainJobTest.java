package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CreditsTrainJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RUN = "train-20240331T233000Z";
    private static final String COARSE = "coarse";
    private static final String DENSE = "dense";
    /** The pair models are fitted in this order, after the five shipping models. */
    private static final int[][] PAIRS = {{0, 1}, {0, 2}, {0, 3}, {0, 4}, {1, 2}, {1, 3}, {1, 4}, {2, 3}, {2, 4},
        {3, 4}};

    @TempDir
    Path temp;

    private GitCommit git;

    @BeforeEach
    void setUp() {
        git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
    }

    /** A fitter that records the rows of each fit and the rows each of its models is asked to predict. */
    private static final class Recorder implements CreditsTrainJob.Fitter {
        private final List<double[][]> rows = new ArrayList<>();
        private final List<int[]> labels = new ArrayList<>();
        /** Each prediction: the fit it came from, then the row's id. */
        private final List<double[]> predictions = new ArrayList<>();
        private final ToDoubleBiFunction<Integer, double[]> answer;

        private Recorder(ToDoubleBiFunction<Integer, double[]> answer) {
            this.answer = answer;
        }

        private Recorder() {
            this((call, row) -> 0.5);
        }

        @Override
        public GradientBoostedTrees fit(double[][] fitRows, int[] fitLabels, GbtParams params) {
            int call = rows.size();
            rows.add(fitRows);
            labels.add(fitLabels);
            GradientBoostedTrees model = mock(GradientBoostedTrees.class);
            when(model.toJson()).thenReturn("{\"call\":" + call + "}");
            when(model.predict(any())).thenAnswer(invocation -> {
                double[] row = invocation.getArgument(0);
                predictions.add(new double[] {call, row[4]});
                return answer.applyAsDouble(call, row);
            });
            return model;
        }

        /** The ids ({@code plain}) of the rows a fit was given, ascending, with their labels. */
        private Map<Integer, Integer> trained(int call) {
            Map<Integer, Integer> out = new TreeMap<>();
            for (int i = 0; i < rows.get(call).length; i++) {
                out.put((int) rows.get(call)[i][4], labels.get(call)[i]);
            }
            return out;
        }

        /** The folds of a fit's rows, when ids are {@code fold * 100 + n}. */
        private Set<Integer> foldsOf(int call) {
            Set<Integer> folds = new TreeSet<>();
            trained(call).keySet().forEach(id -> folds.add(id / 100));
            return folds;
        }
    }

    private static int pairCall(int a, int b) {
        for (int i = 0; i < PAIRS.length; i++) {
            if (PAIRS[i][0] == Math.min(a, b) && PAIRS[i][1] == Math.max(a, b)) {
                return CreditsTrainJob.FOLDS + i;
            }
        }
        throw new IllegalArgumentException();
    }

    private CreditsTrainOptions options(String csv, String... extra) throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, csv);
        return CreditsTrainOptions.parse(TrainFixtures.args(inputs, temp.resolve("out"), extra));
    }

    private List<Path> run(CreditsTrainJob.Fitter fitter, String csv, String... extra) throws IOException {
        return new CreditsTrainJob(TestData.CLOCK, git, fitter).run(options(csv, extra));
    }

    private static String credits(long film, int fold, long offset, double id) {
        return TrainFixtures.row(film, fold, COARSE, offset, "credits", TrainFixtures.lit(id, 0));
    }

    private static String film(long film, int fold, long offset, double id) {
        return TrainFixtures.row(film, fold, COARSE, offset, "film", TrainFixtures.lit(id, 0));
    }

    /**
     * Five folds, one film each (id fold + 1): coarse frames {@code 0..3} labelled credits, film, credits, film, and,
     * when asked, two dense frames. A frame's {@code plain} is {@code fold * 100 + n}, dense ones from 50.
     */
    private static String folded(boolean dense) {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            for (int n = 0; n < 4; n++) {
                csv.append(n % 2 == 0 ? credits(k + 1, k, n * 1000L, k * 100 + n)
                    : film(k + 1, k, n * 1000L, k * 100 + n));
            }
            for (int n = 0; dense && n < 2; n++) {
                csv.append(TrainFixtures.row(k + 1, k, DENSE, n * 500L, "credits",
                    TrainFixtures.lit(k * 100 + 50 + n, 0)));
            }
        }
        return csv.toString();
    }

    /**
     * Film 1 in fold 0 holds every kind of row; the other folds hold two plain coarse rows each. The model of fold 1
     * trains on fold 0 and folds 2 to 4, so its rows below id 20 are fold 0's.
     */
    private String dropFixture() {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        csv.append(TrainFixtures.row(1, 0, COARSE, 0, "credits", TrainFixtures.lit(1, 0)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 1, "film", TrainFixtures.lit(2, 0)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 2, "BOUNDARY", TrainFixtures.lit(3, 5)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 3, "AFTER_LAST_CREDITS", TrainFixtures.lit(4, 5)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 4, "LIT_OPENING", TrainFixtures.lit(5, 3)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 5, "LIT_OPENING", TrainFixtures.lit(6, 2)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 6, "LIT_OPENING", TrainFixtures.plate(7, 3)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 7, "LIT_RUN", TrainFixtures.dark(8, 3)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 8, "LIT_RUN", TrainFixtures.lit(9, 4)));
        csv.append(TrainFixtures.row(1, 0, COARSE, 9, "credits", TrainFixtures.noTime(TrainFixtures.lit(10, 0))));
        csv.append(TrainFixtures.row(1, 0, COARSE, 10, "film", TrainFixtures.lit(11, 0)));
        csv.append(TrainFixtures.row(1, 0, DENSE, 0, "credits", TrainFixtures.lit(12, 0)));
        csv.append(TrainFixtures.row(1, 0, DENSE, 1, "LIT_RUN", TrainFixtures.lit(13, 3)));
        csv.append(TrainFixtures.row(1, 0, DENSE, 2, "credits", TrainFixtures.noTime(TrainFixtures.lit(14, 0))));
        csv.append(TrainFixtures.row(1, 0, DENSE, 3, "BOUNDARY", TrainFixtures.lit(15, 0)));
        for (int k = 1; k < CreditsTrainJob.FOLDS; k++) {
            csv.append(credits(k + 1, k, 0, 20 + k * 2)).append(film(k + 1, k, 1, 21 + k * 2));
        }
        return csv.toString();
    }

    /** The rows of fold 0 that the model of fold 1 trains on, by id, with their labels. */
    private Map<Integer, Integer> fold0Rows(String... extra) throws IOException {
        Recorder recorder = new Recorder();
        run(recorder, dropFixture(), extra);
        Map<Integer, Integer> rows = new TreeMap<>(recorder.trained(1));
        rows.keySet().removeIf(id -> id >= 20);
        return rows;
    }

    @Test
    void byDefaultOnlyCoarseCreditsAndFilmRowsWithTimeFeaturesTrain() throws IOException {
        assertThat(fold0Rows()).containsExactly(Map.entry(1, 1), Map.entry(2, 0), Map.entry(11, 0));
    }

    @Test
    void withDenseAddsTheDenseRowsThatHaveTimeFeatures() throws IOException {
        assertThat(fold0Rows("--with-dense"))
            .containsExactly(Map.entry(1, 1), Map.entry(2, 0), Map.entry(11, 0), Map.entry(12, 1));
    }

    @Test
    void theLitTextVariantRelabelsOnlyLitRowsOfThreeBandsOrMoreAsCredits() throws IOException {
        assertThat(fold0Rows("--lit-text-variant")).containsExactly(Map.entry(1, 1), Map.entry(2, 0),
            Map.entry(5, 1), Map.entry(9, 1), Map.entry(11, 0));
    }

    @Test
    void bothSwitchesTogetherKeepTheDenseLitRunAsCredits() throws IOException {
        assertThat(fold0Rows("--with-dense", "--lit-text-variant")).containsExactly(Map.entry(1, 1),
            Map.entry(2, 0), Map.entry(5, 1), Map.entry(9, 1), Map.entry(11, 0), Map.entry(12, 1),
            Map.entry(13, 1));
    }

    @Test
    void aTestRowStopsTheRunAndNothingIsWritten() {
        String csv = TrainFixtures.csv(credits(1, 0, 0, 1),
            FeatureRows.row(2, null, Side.TEST, COARSE, 0, "BOUNDARY", TrainFixtures.lit(2, 0)));

        assertThatThrownBy(() -> run(new Recorder(), csv))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("features.csv line 3: a row that is not a development row with a fold");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aTestRowIsRefusedEvenWhenItCarriesAFold() {
        String csv = TrainFixtures.csv(
            FeatureRows.row(1, 2, Side.TEST, COARSE, 0, "film", TrainFixtures.lit(1, 0)));

        assertThatThrownBy(() -> run(new Recorder(), csv))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("features.csv line 2: a row that is not a development row with a fold");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aDevelopmentRowWithNoFoldStopsTheRunAndNothingIsWritten() {
        String csv = TrainFixtures.csv(
            FeatureRows.row(1, null, Side.DEV, COARSE, 0, "credits", TrainFixtures.lit(1, 0)));

        assertThatThrownBy(() -> run(new Recorder(), csv))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("features.csv line 2: a row that is not a development row with a fold");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    static Stream<String[]> outOfOrder() {
        String a = TrainFixtures.row(2, 0, COARSE, 5, "film", TrainFixtures.lit(1, 0));
        return Stream.of(
            new String[] {"the same row twice", a, a},
            new String[] {"a lower offset", a, TrainFixtures.row(2, 0, COARSE, 4, "film", TrainFixtures.lit(1, 0))},
            new String[] {"dense before coarse", TrainFixtures.row(2, 0, DENSE, 0, "film", TrainFixtures.lit(1, 0)),
                TrainFixtures.row(2, 0, COARSE, 9, "film", TrainFixtures.lit(1, 0))},
            new String[] {"a lower film id", a, TrainFixtures.row(1, 0, COARSE, 9, "film", TrainFixtures.lit(1, 0))});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outOfOrder")
    void rowsOutOfOrderAreRefusedAndNothingIsWritten(String name, String first, String second) {
        assertThatThrownBy(() -> run(new Recorder(), TrainFixtures.csv(first, second)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv line 3: rows are not in (mediaFileId, kind, offsetMs) order, each after the "
                + "one before");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aFoldWithNoCoarseRowLeftIsRefusedAndNothingIsWritten() {
        String csv = folded(false).lines().filter(line -> !line.startsWith("5,4,")).reduce("",
            (all, line) -> all + line + "\n");

        assertThatThrownBy(() -> run(new Recorder(), csv))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("fold 4 has no coarse row left to train on");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aFoldWithOnlyDenseRowsLeftIsRefusedEvenWithDense() {
        String csv = folded(true).lines().filter(line -> !line.startsWith("5,4,dev,coarse"))
            .reduce("", (all, line) -> all + line + "\n");

        assertThatThrownBy(() -> run(new Recorder(), csv, "--with-dense"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("fold 4 has no coarse row left to train on");
    }

    @Test
    void aNegativeFoldIsRefused() {
        String csv = TrainFixtures.csv(TrainFixtures.row(1, -1, COARSE, 0, "film", TrainFixtures.lit(1, 0)));

        assertThatThrownBy(() -> run(new Recorder(), csv))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fold must be 0 to 4: -1");
    }

    @Test
    void aModelThatCannotBeFittedIsRefusedAndNothingIsWritten() {
        // Every row is credits, so the real fit refuses a single class.
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            csv.append(credits(k + 1, k, 0, k));
        }

        assertThatThrownBy(() -> new CreditsTrainJob(TestData.CLOCK, git).run(options(csv.toString())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both classes");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aRunDirectoryThatAlreadyExistsIsNotOverwritten() throws IOException {
        Files.createDirectories(temp.resolve("out").resolve(RUN));

        assertThatThrownBy(() -> run(new Recorder(), folded(false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("this run's directory already exists");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFiles")
    void aFileThatIsNotWhatAFeaturesRunWritesIsRefused(String name, String csv, String message) {
        assertThatThrownBy(() -> run(new Recorder(), csv))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    static Stream<String[]> badFiles() {
        String good = TrainFixtures.row(1, 0, COARSE, 0, "film", TrainFixtures.lit(1, 0));
        String[] fields = good.strip().split(",", -1);
        return Stream.of(
            new String[] {"no header", good, "does not start with the features header"},
            new String[] {"an empty file", "", "does not start with the features header"},
            new String[] {"a short row", FeatureRows.header() + "1,0,dev\n", "expected 39 fields, found 3"},
            new String[] {"an unknown kind", FeatureRows.header() + good.replace(",coarse,", ",sparse,"),
                "kind must be coarse or dense: sparse"},
            new String[] {"a bad id", FeatureRows.header() + good.replaceFirst("^1,", "x,"),
                "mediaFileId is not a whole number: x"},
            new String[] {"a fold out of range", FeatureRows.header() + good.replaceFirst("^1,0,", "1,5,"),
                "fold must be 0 to 4: 5"},
            new String[] {"a fold of 2^32", FeatureRows.header() + good.replaceFirst("^1,0,", "1,4294967296,"),
                "fold must be 0 to 4: 4294967296"},
            new String[] {"a fold of 1 - 2^32", FeatureRows.header() + good.replaceFirst("^1,0,", "1,-4294967295,"),
                "fold must be 0 to 4: -4294967295"},
            new String[] {"an unknown label", FeatureRows.header() + good.replace(",film,", ",maybe,"),
                "unknown label: maybe"},
            new String[] {"a bad feature", FeatureRows.header() + good.replace("," + fields[11] + ",", ",abc,"),
                "is not a number: abc"},
            new String[] {"an infinite feature", FeatureRows.header() + good.replace("," + fields[11] + ",",
                ",Infinity,"), "is not finite"},
            new String[] {"an empty plain feature", FeatureRows.header() + good.replace("," + fields[10] + ",",
                ",,"), "is not a number: "});
    }

    static Stream<String[]> badSummaries() {
        String v = "\"featureVersion\":" + CreditsFeatures.FEATURE_VERSION;
        String expected = "summary.json has feature version %s, the code has " + CreditsFeatures.FEATURE_VERSION;
        return Stream.of(
            new String[] {"another version", "{\"featureVersion\":99}", expected.formatted("99")},
            new String[] {"no version", "{}", expected.formatted("null")},
            new String[] {"a version that is text", "{\"featureVersion\":\"1\"}", expected.formatted("\"1\"")},
            new String[] {"no dataset hash", "{" + v + ",\"labelsSha256\":\"b\"}",
                "summary.json has no text field datasetSha256"},
            new String[] {"a hash that is not text", "{" + v + ",\"datasetSha256\":\"a\",\"labelsSha256\":7}",
                "summary.json has no text field labelsSha256"});
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badSummaries")
    void aSummaryThatIsNotAFeaturesRunsIsRefused(String name, String summary, String message) throws IOException {
        Path[] inputs = TrainFixtures.inputs(temp, folded(false));
        Files.writeString(inputs[1], summary);
        CreditsTrainOptions options = CreditsTrainOptions.parse(TrainFixtures.args(inputs, temp.resolve("out")));

        assertThatThrownBy(() -> new CreditsTrainJob(TestData.CLOCK, git, new Recorder()).run(options))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
    }

    // ---- no model sees the fold it predicts ----

    @Test
    void everyFitLeavesOutExactlyItsFoldsAndNoPredictionComesFromAModelThatSawItsFold() throws IOException {
        Recorder recorder = new Recorder();

        run(recorder, folded(true), "--with-dense");

        assertThat(recorder.rows).hasSize(15);
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            assertThat(recorder.foldsOf(k)).as("shipping model %d", k)
                .isEqualTo(foldsExcept(k));
        }
        for (int[] pair : PAIRS) {
            assertThat(recorder.foldsOf(pairCall(pair[0], pair[1]))).as("pair %s", List.of(pair[0], pair[1]))
                .isEqualTo(foldsExcept(pair[0], pair[1]));
        }
        Map<Integer, Set<Integer>> modelsByFold = new HashMap<>();
        for (double[] prediction : recorder.predictions) {
            int call = (int) prediction[0];
            int id = (int) prediction[1];
            assertThat(recorder.foldsOf(call)).doesNotContain(id / 100);
            assertThat(id % 100).as("only coarse rows are predicted").isLessThan(50);
            modelsByFold.computeIfAbsent(id / 100, key -> new TreeSet<>()).add(call);
        }
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            Set<Integer> expected = new TreeSet<>();
            for (int j = 0; j < CreditsTrainJob.FOLDS; j++) {
                if (j != k) {
                    expected.add(pairCall(k, j));
                }
            }
            assertThat(modelsByFold.get(k)).as("models predicting fold %d", k).isEqualTo(expected);
        }
        assertThat(recorder.predictions).hasSize(5 * 4 * 4);
    }

    private static Set<Integer> foldsExcept(int... held) {
        Set<Integer> folds = new TreeSet<>(List.of(0, 1, 2, 3, 4));
        for (int h : held) {
            folds.remove(h);
        }
        return folds;
    }

    @Test
    void theLogLossIsTheMeanOverTheFoldOfTheMeanOfTheFourPairModelsThatSkipIt() throws IOException {
        // A pair model {a, b} says 0.1 + 0.1a + 0.02b, so fold k hears the four pairs holding k.
        Recorder recorder = new Recorder((call, row) -> {
            if (call < CreditsTrainJob.FOLDS) {
                return Double.NaN;
            }
            int[] pair = PAIRS[call - CreditsTrainJob.FOLDS];
            return 0.1 + 0.1 * pair[0] + 0.02 * pair[1];
        });

        List<Path> written = run(recorder, folded(false));

        JsonNode loss = MAPPER.readTree(Files.readAllBytes(written.get(3)));
        double[] meanP = {0.15, 0.225, 0.28, 0.315, 0.33};
        double pooled = 0;
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            double expected = (-Math.log(meanP[k]) - Math.log(1 - meanP[k])) / 2;
            JsonNode fold = loss.get("folds").get(Integer.toString(k));
            assertThat(fold.get("rows").intValue()).isEqualTo(4);
            assertThat(fold.get("logLoss").doubleValue()).as("fold %d", k).isCloseTo(expected, within(1e-12));
            pooled += expected / CreditsTrainJob.FOLDS;
        }
        assertThat(loss.get("folds").get("0").get("logLoss").doubleValue()).isCloseTo(1.0298194572, within(1e-9));
        assertThat(loss.get("folds").get("2").get("logLoss").doubleValue()).isCloseTo(0.8007348714, within(1e-9));
        assertThat(loss.get("folds").propertyNames()).containsExactly("0", "1", "2", "3", "4");
        assertThat(loss.get("pooled").get("rows").intValue()).isEqualTo(20);
        assertThat(loss.get("pooled").get("logLoss").doubleValue()).isCloseTo(pooled, within(1e-12));
    }

    @Test
    void theLossOfOnePredictionIsMinusTheLogOfTheProbabilityOfItsLabel() {
        assertThat(CreditsTrainJob.loss(0.8, 1)).isCloseTo(0.2231435513142097, within(1e-15));
        assertThat(CreditsTrainJob.loss(0.8, 0)).isCloseTo(1.6094379124341003, within(1e-15));
    }

    @Test
    void theProbabilityIsClampedToOneE15FromEachEnd() {
        double ceiling = -Math.log(1e-15);
        // 1 - 1e-15 is not exact in a double, so the loss of a film at the top clamp is a little above the ceiling.
        double filmCeiling = -Math.log(1 - (1 - 1e-15));

        assertThat(CreditsTrainJob.loss(0, 1)).isCloseTo(ceiling, within(1e-12));
        assertThat(CreditsTrainJob.loss(-3, 1)).isCloseTo(ceiling, within(1e-12));
        assertThat(CreditsTrainJob.loss(1, 0)).isCloseTo(filmCeiling, within(1e-12));
        assertThat(CreditsTrainJob.loss(4, 0)).isCloseTo(filmCeiling, within(1e-12));
        assertThat(CreditsTrainJob.loss(1, 1)).isPositive().isLessThan(1e-14);
        assertThat(CreditsTrainJob.loss(0, 0)).isPositive().isLessThan(1e-14);
    }

    // ---- the outputs, with the real trees ----

    /** Each fold: four coarse rows, credits when the mean is high; dense rows exist but are not read. */
    private static String separable() {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            for (int n = 0; n < 4; n++) {
                boolean credits = n % 2 == 0;
                csv.append(TrainFixtures.row(k + 1, k, COARSE, n, credits ? "credits" : "film",
                    TrainFixtures.lit(k * 100 + n, credits ? 90 + n + k : 10 + n + k, 0)));
            }
        }
        return csv.toString();
    }

    @Test
    void theOutputsAreWrittenIntoAStampedRunDirectoryWithTheExpectedKeys() throws IOException {
        List<Path> written = run(GradientBoostedTrees::fit, separable(), "--trees", "2", "--depth", "1", "--bins",
            "2", "--min-leaf", "1", "--learning-rate", "0.5", "--lambda", "2");

        Path runDir = temp.resolve("out").toAbsolutePath().normalize().resolve(RUN);
        assertThat(written).containsExactly(runDir, runDir.resolve("model.json"), runDir.resolve("oof-models.json"),
            runDir.resolve("oof-logloss.json"));
        JsonNode model = MAPPER.readTree(Files.readAllBytes(written.get(1)));
        assertThat(model.propertyNames()).containsExactly("type", "featureVersion", "params",
            "withDense", "litTextVariant", "models", "toolGitCommit", "datasetSha256", "labelsSha256",
            "manifestSha256");
        assertThat(model.get("type").stringValue()).isEqualTo("gbt");
        assertThat(model.get("featureVersion").intValue()).isEqualTo(CreditsFeatures.FEATURE_VERSION);
        assertThat(model.get("params").toString())
            .isEqualTo("{\"trees\":2,\"depth\":1,\"learningRate\":0.5,\"bins\":2,\"minLeaf\":1,\"lambda\":2.0}");
        assertThat(model.get("withDense").booleanValue()).isFalse();
        assertThat(model.get("litTextVariant").booleanValue()).isFalse();
        assertThat(model.get("models")).hasSize(5);
        assertThat(model.get("toolGitCommit").stringValue()).isEqualTo("toolsha");
        assertThat(model.get("datasetSha256").stringValue()).isEqualTo("ds-hash");
        assertThat(model.get("labelsSha256").stringValue()).isEqualTo("labels-hash");
        assertThat(model.get("manifestSha256").stringValue()).isEqualTo(TrainFixtures.manifestSha());
        for (JsonNode m : model.get("models")) {
            assertThat(m.propertyNames()).containsExactly("featureVersion", "base", "params", "trees");
            assertThat(m.get("trees")).hasSize(2);
        }

        JsonNode pairs = MAPPER.readTree(Files.readAllBytes(written.get(2)));
        assertThat(pairs.propertyNames()).containsExactly("0,1", "0,2", "0,3", "0,4", "1,2", "1,3",
            "1,4", "2,3", "2,4", "3,4");
        assertThat(pairs.get("2,4").get("trees")).hasSize(2);
    }

    @Test
    void theLogLossFileMatchesWhatTheWrittenPairModelsPredict() throws IOException {
        List<Path> written = new CreditsTrainJob(TestData.CLOCK, git).run(options(separable(), "--trees", "3",
            "--depth", "1", "--bins", "2", "--min-leaf", "1"));

        JsonNode pairs = MAPPER.readTree(Files.readAllBytes(written.get(2)));
        JsonNode loss = MAPPER.readTree(Files.readAllBytes(written.get(3)));
        assertThat(loss.propertyNames()).containsExactly("folds", "pooled");
        assertThat(loss.get("pooled").propertyNames()).containsExactly("rows", "logLoss");
        double total = 0;
        for (int k = 0; k < CreditsTrainJob.FOLDS; k++) {
            double sum = 0;
            for (int n = 0; n < 4; n++) {
                double mean = (n % 2 == 0 ? 90 + n + k : 10 + n + k);
                double[] row = TrainFixtures.lit(k * 100 + n, mean, 0);
                double p = 0;
                for (int j = 0; j < CreditsTrainJob.FOLDS; j++) {
                    if (j != k) {
                        String key = Math.min(j, k) + "," + Math.max(j, k);
                        p += GradientBoostedTrees.fromJson(pairs.get(key).toString()).predict(row);
                    }
                }
                sum += CreditsTrainJob.loss(p / 4, n % 2 == 0 ? 1 : 0);
            }
            JsonNode fold = loss.get("folds").get(Integer.toString(k));
            assertThat(fold.propertyNames()).containsExactly("rows", "logLoss");
            assertThat(fold.get("rows").intValue()).isEqualTo(4);
            assertThat(fold.get("logLoss").doubleValue()).isCloseTo(sum / 4, within(1e-12));
            total += sum;
        }
        assertThat(loss.get("pooled").get("rows").intValue()).isEqualTo(20);
        assertThat(loss.get("pooled").get("logLoss").doubleValue()).isCloseTo(total / 20, within(1e-12));
    }

    @Test
    void theSwitchesAreWrittenIntoTheModel() throws IOException {
        List<Path> written = run(new Recorder(), folded(true), "--with-dense", "--lit-text-variant");

        JsonNode model = MAPPER.readTree(Files.readAllBytes(written.get(1)));
        assertThat(model.get("withDense").booleanValue()).isTrue();
        assertThat(model.get("litTextVariant").booleanValue()).isTrue();
        assertThat(model.get("models").get(3).get("call").intValue()).isEqualTo(3);
    }

    @Test
    void blankLinesInTheFeaturesFileAreSkipped() throws IOException {
        Recorder recorder = new Recorder();

        run(recorder, folded(false).replace("\n", "\n\n"));

        assertThat(recorder.rows).hasSize(15);
    }
}
