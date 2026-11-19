package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.TestData;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CreditsTrainLrJobTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String RUN = "train-lr-20240331T233000Z";
    private static final String COARSE = "coarse";
    private static final String DENSE = "dense";
    private static final int FOLDS = FoldModels.FOLDS;
    private static final long PLENTY = Long.MAX_VALUE;
    /** The pair models are fitted in this order, after the five shipping models. */
    private static final int[][] PAIRS = {{0, 1}, {0, 2}, {0, 3}, {0, 4}, {1, 2}, {1, 3}, {1, 4}, {2, 3}, {2, 4},
        {3, 4}};
    /** The triple models are fitted in this order, after the pair models. */
    private static final int[][] TRIPLES = {{0, 1, 2}, {0, 1, 3}, {0, 1, 4}, {0, 2, 3}, {0, 2, 4}, {0, 3, 4},
        {1, 2, 3}, {1, 2, 4}, {1, 3, 4}, {2, 3, 4}};

    @TempDir
    Path temp;

    private GitCommit git;
    private Path[] inputs;
    private Path embeddings;

    @BeforeEach
    void setUp() {
        git = mock(GitCommit.class);
        when(git.commit()).thenReturn("toolsha");
    }

    // ---- fixtures ----

    /** An embedding that says which data row it belongs to: {@code {index, fold, label, 0.25}}. */
    private static final class Rows {
        private final List<int[]> facts = new ArrayList<>();
        private final StringBuilder csv = new StringBuilder(FeatureRows.header());
        private int next;

        /** Adds a row whose {@code plain} feature is its index; {@code build} makes the features from it. */
        Rows add(int fold, String kind, long offset, String label, java.util.function.DoubleFunction<double[]> build) {
            facts.add(new int[] {next, fold, label.equals("credits") ? 1 : 0});
            csv.append(TrainFixtures.row(fold + 1, fold, kind, offset, label, build.apply(next)));
            next++;
            return this;
        }

        Rows coarse(int fold, long offset, String label) {
            return add(fold, COARSE, offset, label, id -> TrainFixtures.lit(id, 0));
        }

        Rows blankLine() {
            csv.append('\n');
            return this;
        }

        String csv() {
            return csv.toString();
        }

        /** The embeddings, {@code dimension} long (at least 3): the facts, then 0.25 for every other value. */
        IntFunction<float[]> vectors(int dimension) {
            return i -> {
                float[] vector = new float[dimension];
                Arrays.fill(vector, 0.25f);
                for (int j = 0; j < 3; j++) {
                    vector[j] = facts.get(i)[j];
                }
                return vector;
            };
        }
    }

    /**
     * Film 1 in fold 0 holds every kind of row (index = {@code plain}), with a blank line after it; the other folds
     * hold a credits and a film row each. Indices 2, 3, 9 and 13 to 14 are dropped by default.
     */
    private static Rows dropFixture() {
        Rows r = new Rows();
        r.add(0, COARSE, 0, "credits", id -> TrainFixtures.lit(id, 0));
        r.add(0, COARSE, 1, "film", id -> TrainFixtures.lit(id, 0));
        r.add(0, COARSE, 2, "BOUNDARY", id -> TrainFixtures.lit(id, 5));
        r.add(0, COARSE, 3, "AFTER_LAST_CREDITS", id -> TrainFixtures.lit(id, 5));
        r.add(0, COARSE, 4, "LIT_OPENING", id -> TrainFixtures.lit(id, 3));
        r.add(0, COARSE, 5, "LIT_OPENING", id -> TrainFixtures.lit(id, 2));
        r.add(0, COARSE, 6, "LIT_OPENING", id -> TrainFixtures.plate(id, 3));
        r.add(0, COARSE, 7, "LIT_RUN", id -> TrainFixtures.dark(id, 3));
        r.add(0, COARSE, 8, "LIT_RUN", id -> TrainFixtures.lit(id, 4));
        r.add(0, COARSE, 9, "credits", id -> TrainFixtures.noTime(TrainFixtures.lit(id, 0)));
        r.add(0, COARSE, 10, "film", id -> TrainFixtures.lit(id, 0));
        r.add(0, DENSE, 0, "credits", id -> TrainFixtures.lit(id, 0));
        r.add(0, DENSE, 1, "LIT_RUN", id -> TrainFixtures.lit(id, 3));
        r.add(0, DENSE, 2, "credits", id -> TrainFixtures.noTime(TrainFixtures.lit(id, 0)));
        r.add(0, DENSE, 3, "BOUNDARY", id -> TrainFixtures.lit(id, 0));
        r.blankLine();
        for (int k = 1; k < FOLDS; k++) {
            r.coarse(k, 0, "credits").coarse(k, 1, "film");
        }
        return r;
    }

    /** Five folds of four coarse rows (credits, film, credits, film) and, when asked, two dense credits rows. */
    private static Rows folded(boolean dense) {
        Rows r = new Rows();
        for (int k = 0; k < FOLDS; k++) {
            for (int n = 0; n < 4; n++) {
                r.coarse(k, n * 1000L, n % 2 == 0 ? "credits" : "film");
            }
            for (int n = 0; dense && n < 2; n++) {
                r.add(k, DENSE, n * 500L, "credits", id -> TrainFixtures.lit(id, 0));
            }
        }
        return r;
    }

    /** Five folds of {@code perFold} coarse rows, alternating credits and film. */
    private static Rows grid(int perFold) {
        Rows r = new Rows();
        for (int k = 0; k < FOLDS; k++) {
            for (int n = 0; n < perFold; n++) {
                r.coarse(k, n, n % 2 == 0 ? "credits" : "film");
            }
        }
        return r;
    }

    private void prepare(Rows rows, int dimension) throws IOException {
        prepare(rows.csv(), dimension, rows.vectors(dimension));
    }

    private void prepare(String csv, int dimension, IntFunction<float[]> vectors) throws IOException {
        inputs = TrainFixtures.inputs(temp, csv);
        embeddings = LrFixtures.embeddingsRun(temp.resolve("embed-20240101T000000Z"), inputs[0], dimension,
            vectors);
    }

    private CreditsTrainLrOptions options(String... extra) {
        List<String> args = new ArrayList<>(List.of(TrainFixtures.args(inputs, temp.resolve("out"))));
        args.addAll(List.of("--embeddings", embeddings.toString()));
        args.addAll(List.of(extra));
        return CreditsTrainLrOptions.parse(args.toArray(String[]::new));
    }

    private List<Path> run(CreditsTrainLrJob.Fitter fitter, long maxMemory, String... extra) throws IOException {
        return new CreditsTrainLrJob(TestData.CLOCK, git, fitter, () -> maxMemory).run(options(extra));
    }

    private List<Path> run(CreditsTrainLrJob.Fitter fitter, String... extra) throws IOException {
        return run(fitter, PLENTY, extra);
    }

    private void edit(String file, Consumer<ObjectNode> change) throws IOException {
        Path json = embeddings.resolve(file);
        ObjectNode root = (ObjectNode) MAPPER.readTree(Files.readAllBytes(json));
        change.accept(root);
        Files.write(json, MAPPER.writeValueAsBytes(root));
    }

    private static Set<Integer> foldsExcept(int... held) {
        Set<Integer> folds = new TreeSet<>();
        for (int k = 0; k < FOLDS; k++) {
            folds.add(k);
        }
        Arrays.stream(held).forEach(folds::remove);
        return folds;
    }

    private static int pairCall(int a, int b) {
        for (int i = 0; i < PAIRS.length; i++) {
            if (PAIRS[i][0] == Math.min(a, b) && PAIRS[i][1] == Math.max(a, b)) {
                return FOLDS + i;
            }
        }
        throw new IllegalArgumentException();
    }

    /** A fitter that records what each fit was given and which fit each prediction came from. */
    private static final class Recorder implements CreditsTrainLrJob.Fitter {
        private final List<float[][]> rows = new ArrayList<>();
        private final List<int[]> labels = new ArrayList<>();
        /** Each prediction: the fit it came from, the predicted row's fold, then its index. */
        private final List<int[]> predictions = new ArrayList<>();
        private final ToDoubleBiFunction<Integer, float[]> answer;

        private Recorder(ToDoubleBiFunction<Integer, float[]> answer) {
            this.answer = answer;
        }

        private Recorder() {
            this((call, row) -> 0.5);
        }

        @Override
        public LogisticRegression fit(float[][] fitRows, int[] fitLabels, LrParams params) {
            int call = rows.size();
            rows.add(fitRows);
            labels.add(fitLabels);
            LogisticRegression model = mock(LogisticRegression.class);
            when(model.toJson()).thenReturn("{\"call\":" + call + "}");
            when(model.predict(any())).thenAnswer(invocation -> {
                float[] row = invocation.getArgument(0);
                predictions.add(new int[] {call, (int) row[1], (int) row[0]});
                return answer.applyAsDouble(call, row);
            });
            return model;
        }

        /** The indices of the rows a fit was given, in order. */
        private List<Integer> indices(int call) {
            return Arrays.stream(rows.get(call)).map(row -> (int) row[0]).toList();
        }

        private Set<Integer> foldsOf(int call) {
            return Arrays.stream(rows.get(call)).map(row -> (int) row[1]).collect(java.util.stream.Collectors.toSet());
        }
    }

    /** Model A's fitter on the same CSV: it records the {@code plain} feature (the row's index) of each row. */
    private static final class RecorderA implements CreditsTrainJob.Fitter {
        private final List<List<Integer>> indices = new ArrayList<>();
        private final List<List<Integer>> labels = new ArrayList<>();

        @Override
        public GradientBoostedTrees fit(double[][] rows, int[] fitLabels, GbtParams params) {
            indices.add(Arrays.stream(rows).map(row -> (int) row[4]).toList());
            labels.add(Arrays.stream(fitLabels).boxed().toList());
            GradientBoostedTrees model = mock(GradientBoostedTrees.class);
            when(model.toJson()).thenReturn("{}");
            when(model.predict(any())).thenReturn(0.5);
            return model;
        }
    }

    // ---- the same rows as model A ----

    static Stream<Arguments> variants() {
        return Stream.of(Arguments.of("the defaults", new String[] {}),
            Arguments.of("--with-dense", new String[] {"--with-dense"}),
            Arguments.of("--lit-text-variant", new String[] {"--lit-text-variant"}),
            Arguments.of("both switches", new String[] {"--with-dense", "--lit-text-variant"}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void everyFitGetsTheRowsAndLabelsModelAGetsOnTheSameCsv(String name, String[] extra) throws IOException {
        Rows rows = dropFixture();
        prepare(rows, 4);
        RecorderA a = new RecorderA();
        new CreditsTrainJob(TestData.CLOCK, git, a).run(CreditsTrainOptions.parse(
            TrainFixtures.args(inputs, temp.resolve("out-a"), extra)));
        Recorder b = new Recorder();

        run(b, extra);

        assertThat(b.rows).hasSize(25).hasSameSizeAs(a.indices);
        for (int call = 0; call < 25; call++) {
            assertThat(b.indices(call)).as("fit %d rows", call).isEqualTo(a.indices.get(call));
            assertThat(Arrays.stream(b.labels.get(call)).boxed().toList()).as("fit %d labels", call)
                .isEqualTo(a.labels.get(call));
        }
    }

    @Test
    void byDefaultOnlyCoarseCreditsAndFilmRowsWithTimeFeaturesTrain() throws IOException {
        prepare(dropFixture(), 4);
        Recorder recorder = new Recorder();

        run(recorder);

        // Shipping model 1 holds out fold 1; fold 0's rows in it are indices 0 to 14.
        assertThat(recorder.indices(1).stream().filter(i -> i < 15)).containsExactly(0, 1, 10);
    }

    @Test
    void theLitTextVariantAndDenseRowsKeepTheirIndicesAfterABlankLine() throws IOException {
        prepare(dropFixture(), 4);
        Recorder recorder = new Recorder();

        run(recorder, "--with-dense", "--lit-text-variant");

        assertThat(recorder.indices(1).stream().filter(i -> i < 15)).containsExactly(0, 1, 4, 8, 10, 11, 12);
        assertThat(recorder.indices(1).stream().filter(i -> i >= 15)).containsExactly(17, 18, 19, 20, 21, 22);
    }

    // ---- the embeddings must match the rows ----

    private void refused(String message, String... extra) {
        assertThatThrownBy(() -> run(new Recorder(), extra)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(message);
        assertThat(temp.resolve("out")).doesNotExist();
    }

    private String keyMismatch(int index, long[] wrote) throws IOException {
        long[] expected = LrFixtures.keys(inputs[0]).get(index);
        return "embeddings.bin record " + index + " is (mediaFileId " + wrote[0] + ", kind " + wrote[1]
            + ", offsetMs " + wrote[2] + "), features.csv data row " + index + " is (mediaFileId " + expected[0]
            + ", kind " + expected[1] + ", offsetMs " + expected[2] + ")";
    }

    private void keyDiffersAt(int index, long[] wrote) throws IOException {
        Rows rows = dropFixture();
        inputs = TrainFixtures.inputs(temp, rows.csv());
        embeddings = LrFixtures.embeddingsRun(temp.resolve("embed-20240101T000000Z"), inputs[0], 4,
            rows.vectors(4), (i, key) -> i == index ? wrote : key, 0);

        refused(keyMismatch(index, wrote), "--with-dense");
    }

    @Test
    void aKeyThatDiffersOnAKeptRowIsRefusedAndNothingIsWritten() throws IOException {
        keyDiffersAt(10, new long[] {1, 0, 99});
    }

    @Test
    void aKeyThatDiffersOnADroppedRowIsRefusedAndNothingIsWritten() throws IOException {
        keyDiffersAt(2, new long[] {1, 0, 2_000});
    }

    @Test
    void aKeyWhoseKindDiffersIsRefused() throws IOException {
        keyDiffersAt(11, new long[] {1, 0, 0});
    }

    @Test
    void aKeyWhoseMediaFileIdDiffersIsRefused() throws IOException {
        keyDiffersAt(0, new long[] {7, 0, 0});
    }

    @ParameterizedTest
    @MethodSource("recordCounts")
    void aRecordCountOffByOneIsRefusedAndNothingIsWritten(int extra) throws IOException {
        Rows rows = dropFixture();
        inputs = TrainFixtures.inputs(temp, rows.csv());
        embeddings = LrFixtures.embeddingsRun(temp.resolve("embed-20240101T000000Z"), inputs[0], 4,
            rows.vectors(4), (i, key) -> key, extra);

        refused("embeddings.bin has " + (23 + extra) + " records, features.csv has 23 data rows");
    }

    static Stream<Integer> recordCounts() {
        return Stream.of(-1, 1);
    }

    @Test
    void aDimensionThatDiffersFromTheEncodersIsRefusedAndNothingIsWritten() throws IOException {
        prepare(grid(2), 4);
        edit("embeddings.json", root -> ((ObjectNode) root.get("encoder")).put("dimension", 9));

        refused("embeddings.bin has dimension 4, embeddings.json's encoder says 9");
    }

    @Test
    void aFeaturesCsvHashThatDiffersIsRefusedAndNothingIsWritten() throws IOException {
        prepare(grid(2), 4);
        edit("embeddings.json", root -> root.put("featuresCsvSha256", "other"));

        refused("embeddings.json's featuresCsvSha256 is other, features.csv's SHA-256 is "
            + LrFixtures.sha256(inputs[0]));
    }

    @Test
    void aFeaturesCsvThatChangedAfterTheEmbeddingsWereMadeIsRefused() throws IOException {
        prepare(grid(2), 4);
        Files.writeString(inputs[0], Files.readString(inputs[0]) + "\n");

        assertThatThrownBy(() -> run(new Recorder())).isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("embeddings.json's featuresCsvSha256 is ");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aDatasetHashThatDiffersIsRefusedAndNothingIsWritten() throws IOException {
        prepare(grid(2), 4);
        edit("embeddings.json", root -> root.put("datasetSha256", "other"));

        refused("embeddings.json's datasetSha256 is other, summary.json's is ds-hash");
    }

    @Test
    void aLabelsHashThatDiffersIsRefusedAndNothingIsWritten() throws IOException {
        prepare(grid(2), 4);
        edit("embeddings.json", root -> root.put("labelsSha256", "other"));

        refused("embeddings.json's labelsSha256 is other, summary.json's is labels-hash");
    }

    @Test
    void aFeatureVersionThatDiffersIsRefusedAndNothingIsWritten() throws IOException {
        prepare(grid(2), 4);
        edit("embeddings.json", root -> root.put("featureVersion", CreditsFeatures.FEATURE_VERSION + 1));

        refused("embeddings.json has feature version " + (CreditsFeatures.FEATURE_VERSION + 1) + ", the code has "
            + CreditsFeatures.FEATURE_VERSION);
    }

    static Stream<Arguments> badDescriptions() {
        return Stream.of(
            Arguments.of("no encoder", (Consumer<ObjectNode>) root -> root.remove("encoder"),
                "embeddings.json has no object field encoder"),
            Arguments.of("an encoder that is text", (Consumer<ObjectNode>) root -> root.put("encoder", "x"),
                "embeddings.json has no object field encoder"),
            Arguments.of("no featuresCsvSha256", (Consumer<ObjectNode>) root -> root.remove("featuresCsvSha256"),
                "embeddings.json has no text field featuresCsvSha256"),
            Arguments.of("no datasetSha256", (Consumer<ObjectNode>) root -> root.remove("datasetSha256"),
                "embeddings.json has no text field datasetSha256"),
            Arguments.of("a labelsSha256 that is a number", (Consumer<ObjectNode>) root -> root.put("labelsSha256", 7),
                "embeddings.json has no text field labelsSha256"),
            Arguments.of("no devSha256", (Consumer<ObjectNode>) root -> root.remove("devSha256"),
                "embeddings.json has no text field devSha256"),
            Arguments.of("no encoder name", (Consumer<ObjectNode>) root -> ((ObjectNode) root.get("encoder"))
                .remove("name"), "embeddings.json has no text field name"),
            Arguments.of("no encoder sha256", (Consumer<ObjectNode>) root -> ((ObjectNode) root.get("encoder"))
                .remove("sha256"), "embeddings.json has no text field sha256"),
            Arguments.of("a feature version that is text", (Consumer<ObjectNode>) root -> root.put("featureVersion",
                "1"), "embeddings.json has no whole-number field featureVersion"),
            Arguments.of("a dimension that is text", (Consumer<ObjectNode>) root -> ((ObjectNode) root.get("encoder"))
                .put("dimension", "4"), "embeddings.json has no whole-number field dimension"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badDescriptions")
    void anEmbeddingsJsonThatLacksAFieldIsRefusedAndNothingIsWritten(String name, Consumer<ObjectNode> change,
                                                                    String message) throws IOException {
        prepare(grid(2), 4);
        edit("embeddings.json", change);

        refused(message);
    }

    @Test
    void anOutDirectoryInsideTheEmbeddingsRunIsRefused() throws IOException {
        prepare(grid(2), 4);
        Path inside = embeddings.resolve("models");

        CreditsTrainLrOptions options = CreditsTrainLrOptions.parse(new String[] {"--features",
            inputs[0].toString(), "--summary", inputs[1].toString(), "--manifest", inputs[2].toString(),
            "--embeddings", embeddings.toString(), "--out", inside.toString()});

        assertThatThrownBy(() -> new CreditsTrainLrJob(TestData.CLOCK, git, new Recorder(), () -> PLENTY)
            .run(options)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("--out must not be inside the embeddings run: " + inside);
        assertThat(inside).doesNotExist();
    }

    // ---- the memory cap ----

    @Test
    void theEmbeddingsOfTheKeptRowsMustFitIn70PercentOfTheMaximumMemory() throws IOException {
        prepare(grid(5), 7);

        assertThatThrownBy(() -> run(new Recorder(), 999))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the 25 kept rows need 700 bytes of embeddings (rows x dimension x 4), more than 70% of "
                + "the 999 bytes of maximum memory; run with a larger -Xmx");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void exactly70PercentOfTheMaximumMemoryIsAllowed() throws IOException {
        prepare(grid(5), 7);

        assertThat(run(new Recorder(), 1000)).hasSize(5);
    }

    @Test
    void theCapCountsOnlyTheRowsThatTrain() throws IOException {
        prepare(dropFixture(), 4);

        // 23 data rows, 11 kept by default: 11 x 4 x 4 = 176 bytes, which 70% of 252 covers and 70% of 251 does not.
        assertThat(run(new Recorder(), 252)).hasSize(5);
        assertThatThrownBy(() -> run(new Recorder(), 251)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("the 11 kept rows need 176 bytes");
    }

    // ---- the rows ----

    @Test
    void aTestRowStopsTheRunAndNothingIsWritten() throws IOException {
        String csv = TrainFixtures.csv(
            TrainFixtures.row(1, 0, COARSE, 0, "credits", TrainFixtures.lit(1, 0)),
            FeatureRows.row(2, null, Side.TEST, COARSE, 0, "BOUNDARY", TrainFixtures.lit(2, 0)));
        prepare(csv, 2, i -> new float[] {i, 0});

        refused("features.csv line 3: a row that is not a development row with a fold is refused; nothing was "
            + "written");
    }

    @Test
    void aFoldWithNoCoarseRowLeftIsRefusedAndNothingIsWritten() throws IOException {
        Rows rows = grid(2);
        String csv = rows.csv().lines().filter(line -> !line.startsWith("5,4,")).reduce("",
            (all, line) -> all + line + "\n");
        prepare(csv, 4, i -> new float[] {i, 0, 0, 0});

        refused("fold 4 has no coarse row left to train on");
    }

    @Test
    void aFoldWithOnlyDenseRowsLeftIsRefusedEvenWithDense() throws IOException {
        Rows rows = folded(true);
        String csv = rows.csv().lines().filter(line -> !line.startsWith("5,4,dev,coarse"))
            .reduce("", (all, line) -> all + line + "\n");
        prepare(csv, 4, i -> new float[] {i, 0, 0, 0});

        refused("fold 4 has no coarse row left to train on", "--with-dense");
    }

    @Test
    void aModelThatCannotBeFittedIsRefusedAndNothingIsWritten() throws IOException {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < FOLDS; k++) {
            csv.append(TrainFixtures.row(k + 1, k, COARSE, 0, "credits", TrainFixtures.lit(k, 0)));
        }
        prepare(csv.toString(), 2, i -> new float[] {i, 1});

        assertThatThrownBy(() -> new CreditsTrainLrJob(TestData.CLOCK, git).run(options()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both classes");
        assertThat(temp.resolve("out")).doesNotExist();
    }

    @Test
    void aRunDirectoryThatAlreadyExistsIsNotOverwritten() throws IOException {
        prepare(grid(2), 4);
        Path existing = Files.createDirectories(temp.resolve("out").resolve(RUN));
        Files.writeString(existing.resolve("model.json"), "earlier");

        assertThatThrownBy(() -> run(new Recorder()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("this run's directory already exists: " + temp.resolve("out").toAbsolutePath().normalize()
                .resolve(RUN));
        assertThat(existing.resolve("model.json")).hasContent("earlier");
    }

    @Test
    void rowsThatAreDroppedAfterTheLastKeptRowAreStillChecked() throws IOException {
        String csv = grid(2).csv() + TrainFixtures.row(5, 4, COARSE, 99, "BOUNDARY", TrainFixtures.lit(99, 0));
        prepare(csv, 4, i -> new float[] {Math.min(i, 9), i / 2, i % 2 == 0 ? 1 : 0, 0});

        assertThat(run(new Recorder())).hasSize(5);
    }

    @Test
    void aKeyThatDiffersOnADroppedRowAfterTheLastKeptRowIsRefused() throws IOException {
        String csv = grid(2).csv() + TrainFixtures.row(5, 4, COARSE, 99, "BOUNDARY", TrainFixtures.lit(99, 0));
        inputs = TrainFixtures.inputs(temp, csv);
        long[] wrote = {5, 0, 98};
        embeddings = LrFixtures.embeddingsRun(temp.resolve("embed-20240101T000000Z"), inputs[0], 4,
            i -> new float[] {Math.min(i, 9), i / 2, i % 2 == 0 ? 1 : 0, 0}, (i, key) -> i == 10 ? wrote : key, 0);

        refused(keyMismatch(10, wrote));
    }

    @Test
    void aFailureAfterTheDirectoryWasMadeDeletesIt() throws IOException {
        prepare(grid(2), 4);
        int[] fits = {0};
        // The first five fits are the shipping models, written to model.json before the first pair model fails.
        CreditsTrainLrJob.Fitter failing = (rows, labels, params) -> {
            LogisticRegression model = mock(LogisticRegression.class);
            if (fits[0]++ < FOLDS) {
                when(model.toJson()).thenReturn("{}");
            } else {
                when(model.toJson()).thenThrow(new IllegalStateException("boom"));
            }
            when(model.predict(any())).thenReturn(0.5);
            return model;
        };

        assertThatThrownBy(() -> run(failing)).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(temp.resolve("out")).isDirectory();
        assertThat(temp.resolve("out").resolve(RUN)).doesNotExist();
    }

    @Test
    void aDirectoryThatCannotBeDeletedIsAddedToTheFailure() throws IOException {
        prepare(grid(2), 4);
        Path runDir = temp.resolve("out").toAbsolutePath().normalize().resolve(RUN);
        CreditsTrainLrJob.Fitter failing = (rows, labels, params) -> {
            LogisticRegression model = mock(LogisticRegression.class);
            when(model.toJson()).thenAnswer(invocation -> {
                Files.createDirectories(runDir.resolve("stuck").resolve("deeper"));
                throw new IllegalStateException("boom");
            });
            when(model.predict(any())).thenReturn(0.5);
            return model;
        };

        assertThatThrownBy(() -> run(failing)).isInstanceOf(IllegalStateException.class).hasMessage("boom")
            .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
                .allSatisfy(s -> assertThat(s).isInstanceOf(DirectoryNotEmptyException.class)));
    }

    // ---- no model sees the fold it predicts ----

    @Test
    void everyFitLeavesOutExactlyItsFoldsAndNoPredictionComesFromAModelThatSawItsFold() throws IOException {
        Rows rows = folded(true);
        prepare(rows, 4);
        Recorder recorder = new Recorder();

        run(recorder, "--with-dense");

        assertThat(recorder.rows).hasSize(25);
        for (int k = 0; k < FOLDS; k++) {
            assertThat(recorder.foldsOf(k)).as("shipping model %d", k).isEqualTo(foldsExcept(k));
        }
        for (int[] pair : PAIRS) {
            assertThat(recorder.foldsOf(pairCall(pair[0], pair[1]))).as("pair %s", Arrays.toString(pair))
                .isEqualTo(foldsExcept(pair));
        }
        for (int i = 0; i < TRIPLES.length; i++) {
            assertThat(recorder.foldsOf(FOLDS + PAIRS.length + i)).as("triple %s", Arrays.toString(TRIPLES[i]))
                .isEqualTo(foldsExcept(TRIPLES[i]));
        }
        // 20 coarse rows are predicted, each by the four pair models that skip its fold; dense rows never are.
        assertThat(recorder.predictions).hasSize(80);
        for (int k = 0; k < FOLDS; k++) {
            int fold = k;
            Set<Integer> callers = new HashSet<>();
            for (int[] prediction : recorder.predictions) {
                if (prediction[1] == fold) {
                    callers.add(prediction[0]);
                    assertThat(recorder.foldsOf(prediction[0])).as("fit %d predicting fold %d", prediction[0], fold)
                        .doesNotContain(fold);
                }
            }
            Set<Integer> expected = new HashSet<>();
            for (int j = 0; j < FOLDS; j++) {
                if (j != k) {
                    expected.add(pairCall(j, k));
                }
            }
            assertThat(callers).as("the fits that predict fold %d", k).isEqualTo(expected);
        }
        assertThat(recorder.predictions).allSatisfy(p -> assertThat(p[0]).isBetween(FOLDS, FOLDS + PAIRS.length - 1));
        // Each fold's rows are four coarse (index % 6 below 4) then two dense.
        assertThat(recorder.predictions).allSatisfy(p -> assertThat(p[2] % 6).isLessThan(4));
    }

    @Test
    void aPredictionIsGivenTheEmbeddingOfItsOwnRow() throws IOException {
        Rows rows = grid(2);
        prepare(rows, 4);
        List<int[]> seen = new ArrayList<>();
        Recorder recorder = new Recorder((call, row) -> {
            seen.add(new int[] {(int) row[0], (int) row[1]});
            return 0.5;
        });

        run(recorder);

        // Row i = fold * 2 + n; each fold's two rows are each predicted by four models.
        assertThat(seen).hasSize(40);
        assertThat(seen).allSatisfy(s -> assertThat(s[0] / 2).isEqualTo(s[1]));
    }

    // ---- the log loss ----

    static Stream<Arguments> losses() {
        return Stream.of(
            // -ln(0.8) and -ln(0.2) averaged: a credits row and a film row, both predicted 0.8.
            Arguments.of(0.8, 0.8, 0.916290731874155),
            // -ln(0.9) and -ln(1 - 0.5) averaged.
            Arguments.of(0.9, 0.5, 0.3992538481088858));
    }

    @ParameterizedTest
    @MethodSource("losses")
    void theOutOfFoldLogLossIsTheMeanOfTheCoarseRowsLosses(double credits, double film, double expected)
            throws IOException {
        prepare(grid(2), 4);
        Recorder recorder = new Recorder((call, row) -> row[2] == 1 ? credits : film);

        List<Path> written = run(recorder);

        JsonNode logLoss = MAPPER.readTree(Files.readAllBytes(written.get(3)));
        for (int k = 0; k < FOLDS; k++) {
            assertThat(logLoss.get("folds").get(Integer.toString(k)).get("rows").intValue()).isEqualTo(2);
            assertThat(logLoss.get("folds").get(Integer.toString(k)).get("logLoss").doubleValue())
                .isCloseTo(expected, within(1e-12));
        }
        assertThat(logLoss.get("pooled").get("rows").intValue()).isEqualTo(10);
        assertThat(logLoss.get("pooled").get("logLoss").doubleValue()).isCloseTo(expected, within(1e-12));
    }

    // ---- one real fit, end to end ----

    /** Five folds of four rows, credits and film alternating, whose two classes sit on either side of zero. */
    private static String separable() {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int k = 0; k < FOLDS; k++) {
            for (int n = 0; n < 4; n++) {
                csv.append(TrainFixtures.row(k + 1, k, COARSE, n * 1000L, n % 2 == 0 ? "credits" : "film",
                    TrainFixtures.lit(k * 4 + n, 0)));
            }
        }
        return csv.toString();
    }

    private static float[] separableVector(int i) {
        float side = i % 4 % 2 == 0 ? 1f : -1f;
        return new float[] {side + 0.01f * i, 0.1f * (i % 3), 0.5f * side - 0.02f * i};
    }

    @Test
    void aRealRunWritesTheFourFilesOfTheContract() throws IOException {
        prepare(separable(), 3, CreditsTrainLrJobTest::separableVector);

        List<Path> written = new CreditsTrainLrJob(TestData.CLOCK, git).run(options("--max-iterations", "50"));

        Path runDir = temp.resolve("out").toAbsolutePath().normalize().resolve(RUN);
        assertThat(written).containsExactly(runDir, runDir.resolve("model.json"), runDir.resolve("oof-models.json"),
            runDir.resolve("oof-logloss.json"), runDir.resolve("nested-models.json"));
        assertThat(written.subList(1, 5)).allSatisfy(file -> assertThat(file).isRegularFile());
        JsonNode model = MAPPER.readTree(Files.readAllBytes(runDir.resolve("model.json")));
        assertThat(model.propertyNames()).containsExactly("type", "featureVersion", "encoder", "params", "withDense",
            "litTextVariant", "models", "toolGitCommit", "datasetSha256", "labelsSha256", "manifestSha256",
            "embeddingsRun", "embeddingsDevSha256");
        assertThat(model.get("type").stringValue()).isEqualTo("logreg");
        assertThat(model.get("featureVersion").intValue()).isEqualTo(CreditsFeatures.FEATURE_VERSION);
        assertThat(model.get("encoder")).isEqualTo(MAPPER.readTree("{\"name\":\"stub-encoder\",\"sha256\":\""
            + LrFixtures.ENCODER_SHA + "\",\"dimension\":3}"));
        assertThat(model.get("params")).isEqualTo(MAPPER.readTree(
            "{\"l2\":1.0,\"maxIterations\":50,\"tolerance\":1.0E-6}"));
        assertThat(model.get("withDense").booleanValue()).isFalse();
        assertThat(model.get("litTextVariant").booleanValue()).isFalse();
        assertThat(model.get("toolGitCommit").stringValue()).isEqualTo("toolsha");
        assertThat(model.get("datasetSha256").stringValue()).isEqualTo("ds-hash");
        assertThat(model.get("labelsSha256").stringValue()).isEqualTo("labels-hash");
        assertThat(model.get("manifestSha256").stringValue()).isEqualTo(TrainFixtures.manifestSha());
        assertThat(model.get("embeddingsRun").stringValue()).isEqualTo("embed-20240101T000000Z");
        assertThat(model.get("embeddingsDevSha256").stringValue()).isEqualTo(LrFixtures.DEV_SHA);
        assertThat(model.get("models")).hasSize(5);
    }

    @Test
    void everyShippingModelIsTheFitOnTheRowsOfTheOtherFolds() throws IOException {
        prepare(separable(), 3, CreditsTrainLrJobTest::separableVector);
        LrParams params = new LrParams(1.0, 50, 1e-6);

        List<Path> written = new CreditsTrainLrJob(TestData.CLOCK, git).run(options("--max-iterations", "50"));

        JsonNode models = MAPPER.readTree(Files.readAllBytes(written.get(1))).get("models");
        for (int k = 0; k < FOLDS; k++) {
            assertThat(models.get(k)).as("shipping model %d", k)
                .isEqualTo(MAPPER.readTree(fitWithout(params, k).toJson()));
        }
        JsonNode pairs = MAPPER.readTree(Files.readAllBytes(written.get(2)));
        assertThat(pairs.propertyNames()).containsExactly("0,1", "0,2", "0,3", "0,4", "1,2", "1,3", "1,4", "2,3",
            "2,4", "3,4");
        assertThat(pairs.get("1,3")).isEqualTo(MAPPER.readTree(fitWithout(params, 1, 3).toJson()));
        JsonNode triples = MAPPER.readTree(Files.readAllBytes(written.get(4)));
        assertThat(triples.propertyNames()).containsExactly("0,1,2", "0,1,3", "0,1,4", "0,2,3", "0,2,4", "0,3,4",
            "1,2,3", "1,2,4", "1,3,4", "2,3,4");
        assertThat(triples.get("0,2,4")).isEqualTo(MAPPER.readTree(fitWithout(params, 0, 2, 4).toJson()));
        JsonNode logLoss = MAPPER.readTree(Files.readAllBytes(written.get(3)));
        assertThat(logLoss.get("folds").propertyNames()).containsExactly("0", "1", "2", "3", "4");
        assertThat(logLoss.get("pooled").get("rows").intValue()).isEqualTo(20);
    }

    private static LogisticRegression fitWithout(LrParams params, int... held) {
        Set<Integer> out = new HashSet<>();
        Arrays.stream(held).forEach(out::add);
        List<float[]> rows = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        for (int i = 0; i < FOLDS * 4; i++) {
            if (!out.contains(i / 4)) {
                rows.add(separableVector(i));
                labels.add(i % 2 == 0 ? 1 : 0);
            }
        }
        return LogisticRegression.fit(rows.toArray(float[][]::new), labels.stream().mapToInt(Integer::intValue)
            .toArray(), params);
    }

    @Test
    void twoRunsWriteByteIdenticalFiles() throws IOException {
        prepare(separable(), 3, CreditsTrainLrJobTest::separableVector);
        CreditsTrainLrOptions first = options("--max-iterations", "50");
        CreditsTrainLrOptions second = new CreditsTrainLrOptions(first.features(), first.summary(), first.manifest(),
            first.embeddings(), temp.resolve("out-2"), first.params(), first.withDense(), first.litTextVariant());

        List<Path> a = new CreditsTrainLrJob(TestData.CLOCK, git).run(first);
        List<Path> b = new CreditsTrainLrJob(TestData.CLOCK, git).run(second);

        for (int i = 1; i < 5; i++) {
            assertThat(Files.readAllBytes(b.get(i))).as(a.get(i).getFileName().toString())
                .isEqualTo(Files.readAllBytes(a.get(i)));
        }
    }

    @Test
    void theRunDirectoryIsNamedFromTheClockReadOnce() throws IOException {
        prepare(grid(2), 4);
        int[] reads = {0};
        java.time.Clock counting = new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return TestData.CLOCK.getZone();
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                reads[0]++;
                return TestData.CLOCK.instant();
            }
        };

        new CreditsTrainLrJob(counting, git, new Recorder(), () -> PLENTY).run(options());

        assertThat(reads[0]).isEqualTo(1);
    }

}
