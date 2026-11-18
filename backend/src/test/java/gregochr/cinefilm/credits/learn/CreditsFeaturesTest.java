package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.credits.learn.FeatureFixtures.expected;
import static gregochr.cinefilm.credits.learn.FeatureFixtures.of;
import static gregochr.cinefilm.credits.learn.FeatureFixtures.uniform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.learn.FeatureFixtures.Canvas;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreditsFeaturesTest {

    private static final int PER_FRAME = 31;

    /** Three lines of text on a 32 x 24 crop: four rows each, six 2 px strokes from column 4, rows 3, 10 and 17. */
    private static Canvas textLines(int background, int ink) {
        return new Canvas(32, 24, background)
            .text(4, 3, 4, 6, ink)
            .text(4, 10, 4, 6, ink)
            .text(4, 17, 4, 6, ink);
    }

    /** The features of {@link #textLines} on black (ink 255 on 0), without the time features. */
    private static double[] textOnBlackFeatures() {
        return expected("mean", 47.8125, "p10", 0, "p50", 0, "p90", 255, "plain", 624.0 / 768, "ink", 144.0 / 768,
            "hEdge", 144.0 / 744, "vEdge", 72.0 / 736, "thinPerK", 93.75, "thinShare", 1, "textRows", 0.5,
            "bands", 3, "lineSpacingCv", 0, "centralInk", 96.0 / 144, "bottomInk", 12.0 / 144,
            "grid00", 0.125, "grid01", 0.25, "grid02", 0.25, "grid03", 0.125,
            "grid10", 0.125, "grid11", 0.25, "grid12", 0.25, "grid13", 0.125,
            "grid20", 0.125, "grid21", 0.25, "grid22", 0.25, "grid23", 0.125,
            "grid30", 0.125, "grid31", 0.25, "grid32", 0.25, "grid33", 0.125);
    }

    private static void assertPerFrame(double[] actual, double[] expectedFeatures) {
        assertThat(actual).hasSize(CreditsFeatures.COUNT);
        assertThat(Arrays.copyOf(actual, PER_FRAME)).containsExactly(Arrays.copyOf(expectedFeatures, PER_FRAME));
    }

    // ---- the contract --------------------------------------------------------------------------------------------

    @Test
    void theFeatureVersionIsOne() {
        assertThat(CreditsFeatures.FEATURE_VERSION).isEqualTo(1);
    }

    @Test
    void theColumnsAreThe33NamesInTheSpecsOrder() {
        assertThat(CreditsFeatures.COLUMNS).containsExactly("mean", "p10", "p50", "p90", "plain", "ink", "hEdge",
            "vEdge", "thinPerK", "thinShare", "textRows", "bands", "lineSpacingCv", "centralInk", "bottomInk",
            "grid00", "grid01", "grid02", "grid03", "grid10", "grid11", "grid12", "grid13",
            "grid20", "grid21", "grid22", "grid23", "grid30", "grid31", "grid32", "grid33", "mad", "inkChange");
        assertThat(CreditsFeatures.COLUMNS).hasSize(CreditsFeatures.COUNT).hasSize(33);
    }

    @Test
    void theColumnsCannotBeChanged() {
        List<String> columns = CreditsFeatures.COLUMNS;
        assertThatThrownBy(() -> columns.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aFrameWithNoNeighboursHasTheThirtyOnePerFrameFeaturesAndNoTimeFeatures() {
        GreyFrame crop = textLines(0, 255).build();

        double[] features = CreditsFeatures.features(crop);

        assertPerFrame(features, textOnBlackFeatures());
        assertThat(of(features, "mad")).isNaN();
        assertThat(of(features, "inkChange")).isNaN();
        assertThat(CreditsFeatures.hasTimeFeatures(features)).isFalse();
    }

    @Test
    void theTimeFeaturesArePresentWithBothNeighboursAndAbsentWhenOnlyOneIsNaN() {
        GreyFrame crop = uniform(8, 8, 100);
        double[] features = CreditsFeatures.features(crop, crop, crop);

        assertThat(CreditsFeatures.hasTimeFeatures(features)).isTrue();
        double[] noMad = features.clone();
        noMad[CreditsFeatures.MAD] = Double.NaN;
        double[] noInkChange = features.clone();
        noInkChange[CreditsFeatures.INK_CHANGE] = Double.NaN;
        assertThat(CreditsFeatures.hasTimeFeatures(noMad)).isFalse();
        assertThat(CreditsFeatures.hasTimeFeatures(noInkChange)).isFalse();
    }

    @Test
    void theTwoTimeMethodsAgreeOnThePerFrameFeatures() {
        GreyFrame crop = textLines(0, 255).build();

        assertThat(Arrays.copyOf(CreditsFeatures.features(crop, crop, crop), PER_FRAME))
            .containsExactly(Arrays.copyOf(CreditsFeatures.features(crop), PER_FRAME));
    }

    @Test
    void aNeighbourOfAnotherWidthIsRefused() {
        GreyFrame crop = uniform(8, 8, 100);

        assertThatThrownBy(() -> CreditsFeatures.features(crop, uniform(9, 8, 100), crop))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9x8").hasMessageContaining("8x8");
        assertThatThrownBy(() -> CreditsFeatures.features(crop, crop, uniform(9, 8, 100)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNeighbourOfAnotherHeightIsRefused() {
        GreyFrame crop = uniform(8, 8, 100);

        assertThatThrownBy(() -> CreditsFeatures.features(crop, uniform(8, 9, 100), crop))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("8x9");
        assertThatThrownBy(() -> CreditsFeatures.features(crop, crop, uniform(8, 9, 100)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- exact values on small crops -----------------------------------------------------------------------------

    @Test
    void aUniformCropHasNoInkNoEdgesAndEveryRatioWithAZeroDenominatorIsZero() {
        double[] features = CreditsFeatures.features(uniform(8, 8, 100));

        assertPerFrame(features, expected("mean", 100, "p10", 100, "p50", 100, "p90", 100, "plain", 1));
    }

    @Test
    void aWhiteBlockOnBlackHasEveryFeatureExact() {
        GreyFrame crop = new Canvas(8, 8, 0).rect(2, 2, 2, 2, 255).build();

        double[] features = CreditsFeatures.features(crop);

        assertPerFrame(features, expected("mean", 15.9375, "plain", 0.9375, "ink", 0.0625, "hEdge", 4.0 / 56,
            "vEdge", 4.0 / 56, "thinPerK", 31.25, "thinShare", 1, "textRows", 0.25, "bands", 0,
            "centralInk", 1, "grid11", 1));
    }

    @Test
    void theStatisticsAreNearestRankOnARampOf64Values() {
        byte[] ramp = new byte[64];
        for (int i = 0; i < ramp.length; i++) {
            ramp[i] = (byte) i;
        }

        double[] features = CreditsFeatures.features(new GreyFrame(8, 8, ramp));

        // Ranks 7, 32 and 58 of the values 0 to 63 are 6, 31 and 57. B = 31, so plain is 19 to 43: 25 of 64.
        assertPerFrame(features, expected("mean", 31.5, "p10", 6, "p50", 31, "p90", 57, "plain", 25.0 / 64));
    }

    @Test
    void theNegativeOfARampUsesTheSameRanksOnItsOwnValues() {
        byte[] ramp = new byte[64];
        for (int i = 0; i < ramp.length; i++) {
            ramp[i] = (byte) (255 - i);
        }

        double[] features = CreditsFeatures.features(new GreyFrame(8, 8, ramp));

        // Values 192 to 255: ranks 7, 32 and 58 are 198, 223 and 249. B = 223, so plain is 211 to 235: 25 of 64.
        assertPerFrame(features, expected("mean", 223.5, "p10", 198, "p50", 223, "p90", 249, "plain", 25.0 / 64));
    }

    @Test
    void aFlatDarkPictureOnAnOddNumberOfPixelsHasTheMedianInTheLargerHalf() {
        GreyFrame crop = new Canvas(9, 9, 6).rect(0, 0, 4, 9, 4).build();

        double[] features = CreditsFeatures.features(crop);

        // 36 pixels of 4 and 45 of 6: rank 41 is 6; ranks 9 and 73 are 4 and 6. B = 6, |4 - 6| = 2, all plain.
        assertPerFrame(features, expected("mean", (36 * 4 + 45 * 6) / 81.0, "p10", 4, "p50", 6, "p90", 6, "plain", 1));
    }

    @Test
    void theGridCellsFollowTheIntegerBoundariesOnA9By9Crop() {
        Canvas canvas = new Canvas(9, 9, 0);
        // Columns and rows split at 0, 2, 4, 6 and 9, so the cells are 2, 2, 2 and 3 wide and high.
        int[][] ink = {{1, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 5}, {6, 6}, {8, 8}};
        for (int[] p : ink) {
            canvas.pixel(p[0], p[1], 255);
        }

        double[] features = CreditsFeatures.features(canvas.build());

        assertThat(of(features, "grid00")).isEqualTo(1.0 / 4);
        assertThat(of(features, "grid11")).isEqualTo(2.0 / 4);
        assertThat(of(features, "grid22")).isEqualTo(2.0 / 4);
        assertThat(of(features, "grid33")).isEqualTo(2.0 / 9);
        for (String off : List.of("grid01", "grid02", "grid03", "grid10", "grid12", "grid13", "grid20", "grid21",
            "grid23", "grid30", "grid31", "grid32")) {
            assertThat(of(features, off)).isZero();
        }
        // Central columns are [2, 6): four of the seven ink pixels. Bottom rows are [8, 9): one.
        assertThat(of(features, "centralInk")).isEqualTo(4.0 / 7);
        assertThat(of(features, "bottomInk")).isEqualTo(1.0 / 7);
        assertThat(of(features, "ink")).isEqualTo(7.0 / 81);
    }

    @Test
    void theBottomRowsAreTheLastFifthRoundedDownOnA32By23Crop() {
        // 23 / 5 = 4, so rows [19, 23) are the bottom. An ink pixel on row 18 is not, on row 19 it is.
        GreyFrame crop = new Canvas(32, 23, 0).pixel(0, 18, 255).pixel(1, 19, 255).pixel(2, 22, 255).build();

        assertThat(of(CreditsFeatures.features(crop), "bottomInk")).isEqualTo(2.0 / 3);
    }

    @Test
    void aRunThatReachesTheRightEdgeAndRunsInDifferentRowsAreCountedSeparately() {
        // Row 0 ends with ink at x = 7 and row 1 starts with ink at x = 0: two runs of length 1, not one of length 2.
        GreyFrame crop = new Canvas(8, 8, 0).pixel(7, 0, 255).pixel(0, 1, 255).build();

        double[] features = CreditsFeatures.features(crop);

        assertThat(of(features, "thinPerK")).isEqualTo(1000.0 * 2 / 64);
        assertThat(of(features, "thinShare")).isEqualTo(1.0);
    }

    // ---- time features -------------------------------------------------------------------------------------------

    @Test
    void madIsTheMeanAbsoluteDifferenceToBothNeighbours() {
        double[] features = CreditsFeatures.features(uniform(8, 8, 100), uniform(8, 8, 110), uniform(8, 8, 90));

        assertThat(of(features, "mad")).isEqualTo(10.0);
        assertThat(of(features, "inkChange")).isZero();
    }

    @Test
    void madAddsTheTwoNeighboursBeforeDividing() {
        double[] features = CreditsFeatures.features(uniform(8, 8, 100), uniform(8, 8, 104), uniform(8, 8, 100));

        assertThat(of(features, "mad")).isEqualTo(2.0);
    }

    @Test
    void aNeighbourIsJudgedForInkAgainstItsOwnMedian() {
        // The neighbour is 5 rows of 200 and 3 rows of 0: its median is 200, so its 24 zeros are ink. Against the
        // frame's own median (0) they would not be, and the 40 pixels of 200 would be.
        GreyFrame crop = uniform(8, 8, 0);
        GreyFrame neighbour = new Canvas(8, 8, 200).rect(0, 5, 8, 3, 0).build();

        double[] features = CreditsFeatures.features(crop, neighbour, crop);

        assertThat(of(features, "inkChange")).isEqualTo(24.0 / 128);
        assertThat(of(features, "mad")).isEqualTo(40.0 * 200 / 128);
    }

    @Test
    void aCardChangeHasTheCardAgainstBlackOnBothSides() {
        GreyFrame card = textLines(0, 255).build();
        GreyFrame black = uniform(32, 24, 0);

        double[] features = CreditsFeatures.features(card, black, black);

        double[] want = textOnBlackFeatures();
        want[CreditsFeatures.MAD] = 47.8125;
        want[CreditsFeatures.INK_CHANGE] = 0.1875;
        assertThat(features).containsExactly(want);
    }

    @Test
    void aCardWithOneBlackNeighbourHasHalfTheChange() {
        GreyFrame card = textLines(0, 255).build();
        GreyFrame black = uniform(32, 24, 0);

        double[] before = CreditsFeatures.features(card, black, card);
        double[] after = CreditsFeatures.features(card, card, black);

        for (double[] features : List.of(before, after)) {
            assertThat(of(features, "mad")).isEqualTo(36720.0 / 1536);
            assertThat(of(features, "inkChange")).isEqualTo(144.0 / 1536);
        }
    }

    // ---- the plan's synthetic frames -----------------------------------------------------------------------------

    @Test
    void textOnBlack() {
        assertPerFrame(CreditsFeatures.features(textLines(0, 255).build()), textOnBlackFeatures());
    }

    @Test
    void textOnWhiteIsTheNegativeOfTextOnBlack() {
        double[] features = CreditsFeatures.features(textLines(255, 0).build());

        double[] want = textOnBlackFeatures();
        want[CreditsFeatures.COLUMNS.indexOf("mean")] = 255 - 47.8125;
        want[CreditsFeatures.COLUMNS.indexOf("p10")] = 0;
        want[CreditsFeatures.COLUMNS.indexOf("p50")] = 255;
        want[CreditsFeatures.COLUMNS.indexOf("p90")] = 255;
        assertPerFrame(features, want);
    }

    @Test
    void whiteOnLightGreyHasTheEdgesAndTheTextRowsButNoInk() {
        double[] features = CreditsFeatures.features(textLines(200, 255).build());

        // 255 is 55 from 200: under the ink distance of 64, over the edge delta of 48 and the plain distance of 12.
        assertPerFrame(features, expected("mean", 161520.0 / 768, "p10", 200, "p50", 200, "p90", 255,
            "plain", 624.0 / 768, "hEdge", 144.0 / 744, "vEdge", 72.0 / 736, "textRows", 0.5, "bands", 3));
    }

    @Test
    void aBurnedInSubtitleSitsInTheBottomRowsAndTheMiddleColumns() {
        GreyFrame crop = new Canvas(32, 24, 20).text(8, 19, 3, 4, 255).build();

        double[] features = CreditsFeatures.features(crop);

        assertPerFrame(features, expected("mean", 21000.0 / 768, "p10", 20, "p50", 20, "p90", 20,
            "plain", 744.0 / 768, "ink", 24.0 / 768, "hEdge", 24.0 / 744, "vEdge", 16.0 / 736,
            "thinPerK", 1000.0 * 12 / 768, "thinShare", 1, "textRows", 3.0 / 24, "bands", 1,
            "centralInk", 1, "bottomInk", 16.0 / 24, "grid31", 0.25, "grid32", 0.25));
    }

    @Test
    void aBrightTexturedSceneOfSmallStepsIsNotInkNorEdges() {
        Canvas canvas = new Canvas(32, 24, 180);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                canvas.pixel(x, y, (x + y) % 2 == 0 ? 180 : 200);
            }
        }

        assertPerFrame(CreditsFeatures.features(canvas.build()),
            expected("mean", 190, "p10", 180, "p50", 180, "p90", 200, "plain", 0.5));
    }

    @Test
    void aBrightTexturedSceneOfEdgesEverywhereIsNotTextRows() {
        Canvas canvas = new Canvas(32, 24, 100);
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                canvas.pixel(x, y, (x + y) % 2 == 0 ? 100 : 160);
            }
        }

        // Every row has 31 edges, a share of 1: past the 0.6 ceiling. 60 apart is an edge but not ink (64).
        assertPerFrame(CreditsFeatures.features(canvas.build()),
            expected("mean", 130, "p10", 100, "p50", 100, "p90", 160, "plain", 0.5, "hEdge", 1, "vEdge", 1));
    }

    @Test
    void aDarkPictureIsAllPlainWithNoInk() {
        GreyFrame crop = new Canvas(32, 24, 4).rect(16, 0, 16, 24, 6).build();

        assertPerFrame(CreditsFeatures.features(crop),
            expected("mean", 5, "p10", 4, "p50", 4, "p90", 6, "plain", 1));
    }

    // ---- text-row bands and line spacing -------------------------------------------------------------------------

    /** An 8 x 24 crop with one ink pixel at column 3 on each of the given rows of text. */
    private static GreyFrame bandsAt(int[]... firstAndLast) {
        Canvas canvas = new Canvas(8, 24, 0);
        for (int[] band : firstAndLast) {
            canvas.rect(3, band[0], 1, band[1] - band[0] + 1, 255);
        }
        return canvas.build();
    }

    @Test
    void threeEvenlySpacedBandsHaveNoSpacingVariation() {
        double[] features = CreditsFeatures.features(bandsAt(new int[] {0, 2}, new int[] {4, 6}, new int[] {8, 10}));

        assertThat(of(features, "bands")).isEqualTo(3);
        assertThat(of(features, "lineSpacingCv")).isZero();
    }

    @Test
    void threeUnevenBandsHaveTheirCentreGapsCv() {
        // Centres 1, 5 and 11: gaps 4 and 6, mean 5, variance 1, so sqrt(1) / 5.
        double[] features = CreditsFeatures.features(bandsAt(new int[] {0, 2}, new int[] {4, 6}, new int[] {10, 12}));

        assertThat(of(features, "lineSpacingCv")).isEqualTo(1.0 / 5);
    }

    @Test
    void aBandOfFourRowsHasAHalfRowCentre() {
        // Centres 1.5, 6.5 and 12.5: gaps 5 and 6, mean 5.5, variance 0.25.
        double[] features = CreditsFeatures.features(
            bandsAt(new int[] {0, 3}, new int[] {5, 8}, new int[] {11, 14}));

        assertThat(of(features, "lineSpacingCv")).isEqualTo(0.5 / 5.5);
    }

    @Test
    void fourBandsUseTheMeanAndVarianceOverAllThreeGaps() {
        // Centres 1, 5, 11 and 15: gaps 4, 6 and 4, mean 14 / 3, variance 8 / 9.
        double[] features = CreditsFeatures.features(
            bandsAt(new int[] {0, 2}, new int[] {4, 6}, new int[] {10, 12}, new int[] {14, 16}));

        assertThat(of(features, "bands")).isEqualTo(4);
        assertThat(of(features, "lineSpacingCv")).isCloseTo(Math.sqrt(8.0 / 9) / (14.0 / 3), within(1e-15));
    }

    @Test
    void twoBandsAndOneBandHaveNoSpacingVariation() {
        assertThat(of(CreditsFeatures.features(bandsAt(new int[] {0, 2}, new int[] {5, 7})), "lineSpacingCv"))
            .isZero();
        assertThat(of(CreditsFeatures.features(bandsAt(new int[] {0, 2})), "lineSpacingCv")).isZero();
    }

    @Test
    void aBandTooShortIsNotCountedButItsRowsAre() {
        // Rows 0-1 are a band of 2 rows (too short); rows 3-5 are 3 rows. Heights 20 and 21: boundary test.
        double[] features = CreditsFeatures.features(
            bandsAt(new int[] {0, 1}, new int[] {3, 5}));

        assertThat(of(features, "bands")).isEqualTo(1);
        assertThat(of(features, "textRows")).isEqualTo(5.0 / 24);
    }

    // ---- zero denominators ---------------------------------------------------------------------------------------

    @Test
    void aOnePixelWideCropHasZeroEdgeAndCellRatiosWhereTheDenominatorIsZero() {
        // 1 x 10: six 0s and four 255s. Rank 5 is 0, so B = 0 and the four 255s are ink.
        GreyFrame crop = new Canvas(1, 10, 0).rect(0, 6, 1, 4, 255).build();

        double[] features = CreditsFeatures.features(crop);

        // hEdge has (w - 1) * h = 0 pairs; vEdge has 1 * 9, one of them an edge. Columns [0, 0) hold no ink.
        // Every row has e = 0 and w - 1 = 0, so by the formulas every row is a text row: one band of 10 rows.
        // Column cells are [0, 0), [0, 0), [0, 0) and [0, 1); row cells [0, 2), [2, 5), [5, 7) and [7, 10).
        assertPerFrame(features, expected("mean", 102, "p10", 0, "p50", 0, "p90", 255, "plain", 0.6, "ink", 0.4,
            "vEdge", 1.0 / 9, "thinPerK", 1000.0 * 4 / 10, "thinShare", 1, "textRows", 1, "bands", 1,
            "bottomInk", 2.0 / 4, "grid23", 1.0 / 2, "grid33", 1));
    }

    @Test
    void aOnePixelHighCropHasNoVerticalEdgePairsAndNoBottomRows() {
        // 10 x 1: five 0s then five 255s. n is even, rank 5 is 0, so B = 0.
        GreyFrame crop = new Canvas(10, 1, 0).rect(5, 0, 5, 1, 255).build();

        double[] features = CreditsFeatures.features(crop);

        assertThat(of(features, "vEdge")).isZero();
        assertThat(of(features, "hEdge")).isEqualTo(1.0 / 9);
        // h / 5 = 0, so the bottom rows [1, 1) are empty.
        assertThat(of(features, "bottomInk")).isZero();
        assertThat(of(features, "ink")).isEqualTo(0.5);
    }
}
