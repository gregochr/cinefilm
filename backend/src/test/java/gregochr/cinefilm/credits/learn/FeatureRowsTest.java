package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeatureRowsTest {

    private static final int SIDE = 16;

    private static GreyFrame flat(int level) {
        return FeatureFixtures.uniform(SIDE, SIDE, level);
    }

    /** A loader of flat frames that records the indices it was asked for. */
    private static final class Levels implements FeatureRows.Loader {
        private final List<Integer> levels;
        private final List<Integer> loaded = new ArrayList<>();

        private Levels(List<Integer> levels) {
            this.levels = levels;
        }

        @Override
        public GreyFrame load(int index) {
            loaded.add(index);
            return flat(levels.get(index));
        }
    }

    private static List<Integer> squares(int n) {
        List<Integer> levels = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            levels.add(i * i / 8);
        }
        return levels;
    }

    /** What the sink saw of one dense frame: its index, and the levels of the frame and its two neighbours. */
    private record Seen(int index, int level, Integer previous, Integer next) {
    }

    private static List<Seen> dense(int n, Levels loader) throws IOException {
        List<Seen> seen = new ArrayList<>();
        FeatureRows.dense(n, loader, (index, crop, previous, next) -> seen.add(new Seen(index, crop.luma(0, 0),
            previous == null ? null : previous.luma(0, 0), next == null ? null : next.luma(0, 0))));
        return seen;
    }

    // ---- The header and a row ------------------------------------------------------------------------------------

    @Test
    void theHeaderIsTheSixColumnsThenThe33Features() {
        List<String> expected = new ArrayList<>(List.of("mediaFileId", "fold", "side", "kind", "offsetMs", "label"));
        expected.addAll(CreditsFeatures.COLUMNS);

        assertThat(FeatureRows.COLUMNS).isEqualTo(expected).hasSize(39);
        assertThat(FeatureRows.header()).isEqualTo(String.join(",", expected) + "\n");
    }

    @Test
    void aRowHasTheSixFieldsThenEachFeatureWrittenWithDoubleToString() {
        double[] features = new double[CreditsFeatures.COUNT];
        features[0] = 60.0;
        features[1] = 1.0E-4;
        features[2] = 0.1 + 0.2;

        String row = FeatureRows.row(12, 3, Side.DEV, "dense", 104_500, "LIT_RUN", features);

        assertThat(row).isEqualTo("12,3,dev,dense,104500,LIT_RUN,60.0,1.0E-4,0.30000000000000004"
            + ",0.0".repeat(CreditsFeatures.COUNT - 3) + "\n");
    }

    @Test
    void aNanFeatureAndANullFoldAreEmptyFields() {
        double[] features = CreditsFeatures.features(flat(60));

        String row = FeatureRows.row(1, null, Side.DEV, "coarse", 0, "film", features);

        assertThat(row).isEqualTo("1,,dev,coarse,0,film,60.0,60.0,60.0,60.0,1.0" + ",0.0".repeat(26) + ",,\n");
    }

    // ---- Features with or without neighbours --------------------------------------------------------------------

    @Test
    void featuresWithoutNeighboursAreTheFramesAloneWithNanTimeFeatures() {
        double[] alone = FeatureRows.features(flat(20), null, null);

        assertThat(alone).isEqualTo(CreditsFeatures.features(flat(20)));
        assertThat(CreditsFeatures.hasTimeFeatures(alone)).isFalse();
    }

    @Test
    void featuresWithNeighboursAreTheFramesAgainstThem() {
        double[] between = FeatureRows.features(flat(20), flat(10), flat(40));

        assertThat(between).isEqualTo(CreditsFeatures.features(flat(20), flat(10), flat(40)));
        assertThat(mad(between)).isEqualTo(15.0);
    }

    // ---- Coarse --------------------------------------------------------------------------------------------------

    private static double mad(double[] features) {
        return features[CreditsFeatures.COLUMNS.indexOf("mad")];
    }

    @Test
    void coarseFramesUseTheirNeighboursAndAnEndUsesTheOneNeighbourTwice() throws IOException {
        Levels loader = new Levels(List.of(60, 2, 2, 60, 60, 60, 60, 60));
        List<Long> offsets = List.of(0L, 5L, 10L, 15L, 20L, 25L, 30L, 35L);

        FeatureRows.Coarse coarse = FeatureRows.coarse(offsets, loader);

        assertThat(coarse.features().stream().map(FeatureRowsTest::mad)).containsExactly(58.0, 29.0, 29.0, 29.0, 0.0,
            0.0, 0.0, 0.0);
        assertThat(coarse.features().get(1)).isEqualTo(CreditsFeatures.features(flat(2), flat(60), flat(2)));
        assertThat(coarse.features().get(7)).isEqualTo(CreditsFeatures.features(flat(60), flat(60), flat(60)));
    }

    @Test
    void coarseKindsCarryTheOffsetsAndEachFramesKind() throws IOException {
        Levels loader = new Levels(List.of(60, 2, 200));

        FeatureRows.Coarse coarse = FeatureRows.coarse(List.of(100L, 105L, 110L), loader);

        assertThat(coarse.kinds()).containsExactly(new KindAt(100, FrameKind.LIT), new KindAt(105, FrameKind.DARK),
            new KindAt(110, FrameKind.PLATE));
    }

    @Test
    void twoCoarseFramesAreEachOthersOneNeighbourTwice() throws IOException {
        FeatureRows.Coarse coarse = FeatureRows.coarse(List.of(0L, 5L), new Levels(List.of(10, 30)));

        assertThat(coarse.features().stream().map(FeatureRowsTest::mad)).containsExactly(20.0, 20.0);
    }

    @Test
    void oneCoarseFrameHasNoTimeFeatures() throws IOException {
        FeatureRows.Coarse coarse = FeatureRows.coarse(List.of(0L), new Levels(List.of(10)));

        assertThat(coarse.features()).hasSize(1);
        assertThat(CreditsFeatures.hasTimeFeatures(coarse.features().getFirst())).isFalse();
    }

    @Test
    void noCoarseFramesGiveNothingAndLoadNothing() throws IOException {
        Levels loader = new Levels(List.of());

        FeatureRows.Coarse coarse = FeatureRows.coarse(List.of(), loader);

        assertThat(coarse.kinds()).isEmpty();
        assertThat(coarse.features()).isEmpty();
        assertThat(loader.loaded).isEmpty();
    }

    @Test
    void eachCoarseFrameIsLoadedOnceInOrder() throws IOException {
        Levels loader = new Levels(List.of(1, 2, 3, 4, 5));

        FeatureRows.coarse(List.of(0L, 1L, 2L, 3L, 4L), loader);

        assertThat(loader.loaded).containsExactly(0, 1, 2, 3, 4);
    }

    // ---- Dense ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "5,0", "10,0", "11,0", "20,0", "21,1", "22,2", "30,10"})
    void aDenseListOfNFramesGivesTimeFeaturesToNMinus20OfThem(int n, int withTime) throws IOException {
        List<Seen> seen = dense(n, new Levels(squares(Math.max(n, 1))));

        assertThat(seen).hasSize(n);
        assertThat(seen.stream().filter(frame -> frame.previous() != null)).hasSize(withTime);
        assertThat(seen.stream().map(Seen::index).toList()).isEqualTo(java.util.stream.IntStream.range(0, n).boxed()
            .toList());
    }

    @Test
    void aDenseFramesNeighboursAreTheFramesTenIndicesBeforeAndAfter() throws IOException {
        List<Integer> levels = squares(30);

        List<Seen> seen = dense(30, new Levels(levels));

        for (Seen frame : seen) {
            int i = frame.index();
            assertThat(frame.level()).as("frame " + i).isEqualTo(levels.get(i));
            boolean both = i >= 10 && i + 10 < 30;
            assertThat(frame.previous()).as("previous of " + i).isEqualTo(both ? levels.get(i - 10) : null);
            assertThat(frame.next()).as("next of " + i).isEqualTo(both ? levels.get(i + 10) : null);
        }
    }

    @Test
    void eachDenseFrameIsLoadedOnceInOrder() throws IOException {
        Levels loader = new Levels(squares(25));

        dense(25, loader);

        assertThat(loader.loaded).isEqualTo(java.util.stream.IntStream.range(0, 25).boxed().toList());
    }
}
