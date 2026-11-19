package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainRowsTest {

    private static final String COARSE = "coarse";
    private static final String DENSE = "dense";

    @TempDir
    Path temp;

    /**
     * Eight data rows. Index 0 and 1 train; 2 is left out; 3 is a lit opening of three bands (credits only under the
     * variant); 4 has no time features; 5 is dense and trains with dense; 6 is dense without time features; 7 is
     * film 2's, in fold 3.
     */
    private static String fixture() {
        return TrainFixtures.csv(
            TrainFixtures.row(1, 0, COARSE, 0, "credits", TrainFixtures.lit(1, 0)),
            TrainFixtures.row(1, 0, COARSE, 1, "film", TrainFixtures.lit(2, 0)),
            TrainFixtures.row(1, 0, COARSE, 2, "BOUNDARY", TrainFixtures.lit(3, 5)),
            TrainFixtures.row(1, 0, COARSE, 3, "LIT_OPENING", TrainFixtures.lit(4, 3)),
            TrainFixtures.row(1, 0, COARSE, 4, "credits", TrainFixtures.noTime(TrainFixtures.lit(5, 0))),
            TrainFixtures.row(1, 0, DENSE, 0, "credits", TrainFixtures.lit(6, 0)),
            TrainFixtures.row(1, 0, DENSE, 1, "film", TrainFixtures.noTime(TrainFixtures.lit(7, 0))),
            TrainFixtures.row(2, 3, COARSE, 0, "film", TrainFixtures.lit(8, 0)));
    }

    private TrainRows read(String csv, boolean withDense, boolean litText) throws IOException {
        return TrainRows.read(Files.writeString(temp.resolve("features.csv"), csv), withDense, litText);
    }

    private static List<Integer> indices(TrainRows rows) {
        return rows.kept().stream().map(TrainRows.Kept::index).toList();
    }

    @Test
    void theKeysHoldEveryDataRowInFileOrderKeptOrDropped() throws IOException {
        TrainRows rows = read(fixture(), false, false);

        assertThat(rows.mediaFileIds()).containsExactly(1, 1, 1, 1, 1, 1, 1, 2);
        assertThat(rows.kinds()).containsExactly(0, 0, 0, 0, 0, 1, 1, 0);
        assertThat(rows.offsetsMs()).containsExactly(0, 1, 2, 3, 4, 0, 1, 0);
        assertThat(indices(rows)).containsExactly(0, 1, 7);
    }

    @Test
    void theKindsUseZeroForCoarseAndOneForDense() {
        assertThat(TrainRows.COARSE_KIND).isEqualTo((byte) 0);
        assertThat(TrainRows.DENSE_KIND).isEqualTo((byte) 1);
    }

    @Test
    void theKeysAreTheSameWhateverTheSwitches() throws IOException {
        TrainRows plain = read(fixture(), false, false);
        TrainRows both = read(fixture(), true, true);

        assertThat(both.mediaFileIds()).isEqualTo(plain.mediaFileIds());
        assertThat(both.kinds()).isEqualTo(plain.kinds());
        assertThat(both.offsetsMs()).isEqualTo(plain.offsetsMs());
    }

    @Test
    void theKeptRowsCarryTheirFoldKindLabelAndFeatures() throws IOException {
        TrainRows rows = read(fixture(), true, false);

        assertThat(indices(rows)).containsExactly(0, 1, 5, 7);
        TrainRows.Kept first = rows.kept().get(0);
        assertThat(first.fold()).isZero();
        assertThat(first.coarse()).isTrue();
        assertThat(first.label()).isEqualTo(1);
        assertThat(first.features()).containsExactly(TrainFixtures.lit(1, 0));
        TrainRows.Kept dense = rows.kept().get(2);
        assertThat(dense.coarse()).isFalse();
        assertThat(dense.label()).isEqualTo(1);
        assertThat(dense.features()[4]).isEqualTo(6);
        TrainRows.Kept last = rows.kept().get(3);
        assertThat(last.fold()).isEqualTo(3);
        assertThat(last.label()).isZero();
    }

    @Test
    void theLitTextVariantRelabelsTheLitOpeningAsCredits() throws IOException {
        TrainRows rows = read(fixture(), false, true);

        assertThat(indices(rows)).containsExactly(0, 1, 3, 7);
        assertThat(rows.kept().get(2).label()).isEqualTo(1);
        assertThat(rows.kept().get(2).features()[4]).isEqualTo(4);
    }

    @Test
    void blankLinesAreNotCountedInTheIndices() throws IOException {
        TrainRows rows = read(fixture().replace("\n", "\n\n"), true, false);

        assertThat(indices(rows)).containsExactly(0, 1, 5, 7);
        assertThat(rows.mediaFileIds()).hasSize(8);
    }

    @Test
    void aFileWithOnlyAHeaderHasNoRows() throws IOException {
        TrainRows rows = read(TrainFixtures.csv(), true, true);

        assertThat(rows.kept()).isEmpty();
        assertThat(rows.mediaFileIds()).isEmpty();
        assertThat(rows.kinds()).isEmpty();
        assertThat(rows.offsetsMs()).isEmpty();
    }

    @Test
    void theKeysGrowPastTheirFirstCapacity() throws IOException {
        StringBuilder csv = new StringBuilder(FeatureRows.header());
        for (int n = 0; n < 1500; n++) {
            csv.append(TrainFixtures.row(7, 1, COARSE, n, n % 2 == 0 ? "credits" : "film", TrainFixtures.lit(n, 0)));
        }

        TrainRows rows = read(csv.toString(), false, false);

        assertThat(rows.mediaFileIds()).hasSize(1500);
        assertThat(rows.offsetsMs()[1499]).isEqualTo(1499);
        assertThat(rows.mediaFileIds()[1499]).isEqualTo(7);
        assertThat(rows.kept()).hasSize(1500);
        assertThat(rows.kept().get(1499).index()).isEqualTo(1499);
    }

    @Test
    void aFileWithoutTheHeaderIsRefused() {
        String good = TrainFixtures.row(1, 0, COARSE, 0, "film", TrainFixtures.lit(1, 0));

        assertThatThrownBy(() -> read(good, false, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv does not start with the features header");
    }

    @Test
    void anEmptyFileIsRefused() {
        assertThatThrownBy(() -> read("", false, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv does not start with the features header");
    }

    @Test
    void rowsOutOfOrderAreRefusedWithTheLineNumber() {
        String csv = TrainFixtures.csv(
            TrainFixtures.row(2, 0, COARSE, 5, "film", TrainFixtures.lit(1, 0)),
            TrainFixtures.row(2, 0, COARSE, 4, "film", TrainFixtures.lit(1, 0)));

        assertThatThrownBy(() -> read(csv, false, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv line 3: rows are not in (mediaFileId, kind, offsetMs) order, each after the "
                + "one before");
    }

    @Test
    void aTestRowIsRefused() {
        String csv = TrainFixtures.csv(
            TrainFixtures.row(1, 0, COARSE, 0, "credits", TrainFixtures.lit(1, 0)),
            FeatureRows.row(2, null, Side.TEST, COARSE, 0, "BOUNDARY", TrainFixtures.lit(2, 0)));

        assertThatThrownBy(() -> read(csv, false, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv line 3: a row that is not a development row with a fold is refused; "
                + "nothing was written");
    }

    @Test
    void aDevelopmentRowWithNoFoldIsRefused() {
        String csv = TrainFixtures.csv(
            FeatureRows.row(1, null, Side.DEV, COARSE, 0, "credits", TrainFixtures.lit(1, 0)));

        assertThatThrownBy(() -> read(csv, false, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv line 2: a row that is not a development row with a fold is refused; "
                + "nothing was written");
    }

    @Test
    void aFoldOutOfRangeIsRefused() {
        String csv = TrainFixtures.csv(TrainFixtures.row(1, 5, COARSE, 0, "film", TrainFixtures.lit(1, 0)));

        assertThatThrownBy(() -> read(csv, false, false)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv line 2: fold must be 0 to 4: 5");
    }

    @Test
    void aShortRowIsRefused() {
        assertThatThrownBy(() -> read(FeatureRows.header() + "1,0,dev\n", false, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("features.csv line 2: expected 39 fields, found 3");
    }
}
