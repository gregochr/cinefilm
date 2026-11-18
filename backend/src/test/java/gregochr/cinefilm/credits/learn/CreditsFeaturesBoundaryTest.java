package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.credits.learn.FeatureFixtures.of;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.learn.FeatureFixtures.Canvas;
import org.junit.jupiter.api.Test;

/** One below, at and one above each threshold of {@link CreditsFeatures}. */
class CreditsFeaturesBoundaryTest {

    /** An 8 x 8 crop of 100 with one pixel of the given value at (3, 3). The median stays 100. */
    private static double[] onePixel(int value) {
        return CreditsFeatures.features(new Canvas(8, 8, 100).pixel(3, 3, value).build());
    }

    @Test
    void inkIsAtLeast64FromTheMedianInBothDirections() {
        assertThat(of(onePixel(163), "ink")).isZero();
        assertThat(of(onePixel(164), "ink")).isEqualTo(1.0 / 64);
        assertThat(of(onePixel(165), "ink")).isEqualTo(1.0 / 64);
        assertThat(of(onePixel(37), "ink")).isZero();
        assertThat(of(onePixel(36), "ink")).isEqualTo(1.0 / 64);
        assertThat(of(onePixel(35), "ink")).isEqualTo(1.0 / 64);
    }

    @Test
    void plainIsAtMost12FromTheMedianInBothDirections() {
        assertThat(of(onePixel(111), "plain")).isEqualTo(1.0);
        assertThat(of(onePixel(112), "plain")).isEqualTo(1.0);
        assertThat(of(onePixel(113), "plain")).isEqualTo(63.0 / 64);
        assertThat(of(onePixel(89), "plain")).isEqualTo(1.0);
        assertThat(of(onePixel(88), "plain")).isEqualTo(1.0);
        assertThat(of(onePixel(87), "plain")).isEqualTo(63.0 / 64);
    }

    @Test
    void anEdgeIsAStepOfAtLeast48InBothDirections() {
        // The pixel at (3, 3) has two horizontal and two vertical neighbours: 2 of 56 pairs each way.
        assertThat(of(onePixel(147), "hEdge")).isZero();
        assertThat(of(onePixel(148), "hEdge")).isEqualTo(2.0 / 56);
        assertThat(of(onePixel(149), "hEdge")).isEqualTo(2.0 / 56);
        assertThat(of(onePixel(53), "hEdge")).isZero();
        assertThat(of(onePixel(52), "hEdge")).isEqualTo(2.0 / 56);
        assertThat(of(onePixel(147), "vEdge")).isZero();
        assertThat(of(onePixel(148), "vEdge")).isEqualTo(2.0 / 56);
        assertThat(of(onePixel(149), "vEdge")).isEqualTo(2.0 / 56);
        assertThat(of(onePixel(53), "vEdge")).isZero();
        assertThat(of(onePixel(52), "vEdge")).isEqualTo(2.0 / 56);
    }

    /** An 8 x 8 crop of black: an ink run of the given length at the left of row 0, one ink pixel in row 2. */
    private static double[] runOf(int length) {
        return CreditsFeatures.features(new Canvas(8, 8, 0).rect(0, 0, length, 1, 255).pixel(0, 2, 255).build());
    }

    @Test
    void aRunIsThinUpToSixPixelsAndNotAtSeven() {
        assertThat(of(runOf(5), "thinShare")).isEqualTo(1.0);
        assertThat(of(runOf(6), "thinShare")).isEqualTo(1.0);
        assertThat(of(runOf(6), "thinPerK")).isEqualTo(1000.0 * 2 / 64);
        assertThat(of(runOf(7), "thinShare")).isEqualTo(0.5);
        assertThat(of(runOf(7), "thinPerK")).isEqualTo(1000.0 * 1 / 64);
    }

    /** An 8 x 8 crop of black: an ink run of the given length ending at the right edge of row 0, one pixel in row 2. */
    private static double[] runAtTheRightEdge(int length) {
        return CreditsFeatures.features(
            new Canvas(8, 8, 0).rect(8 - length, 0, length, 1, 255).pixel(0, 2, 255).build());
    }

    @Test
    void aRunEndingAtTheRightEdgeIsThinUpToSixPixelsAndNotAtSeven() {
        assertThat(of(runAtTheRightEdge(6), "thinShare")).isEqualTo(1.0);
        assertThat(of(runAtTheRightEdge(7), "thinShare")).isEqualTo(0.5);
        assertThat(of(runAtTheRightEdge(5), "thinShare")).isEqualTo(1.0);
    }

    @Test
    void aRunThatFillsTheRowIsNotThinEither() {
        assertThat(of(runOf(8), "thinShare")).isEqualTo(0.5);
    }

    /** An 8 x 24 crop of black with one ink pixel at column 3 on rows {@code [2, 2 + height)}. */
    private static double[] bandOf(int height) {
        return CreditsFeatures.features(new Canvas(8, 24, 0).rect(3, 2, 1, height, 255).build());
    }

    @Test
    void aBandIsCountedFromThreeRowsToTwentyAndNotTwoOrTwentyOne() {
        assertThat(of(bandOf(2), "bands")).isZero();
        assertThat(of(bandOf(3), "bands")).isEqualTo(1);
        assertThat(of(bandOf(20), "bands")).isEqualTo(1);
        assertThat(of(bandOf(21), "bands")).isZero();
    }

    @Test
    void textRowsCountRowsOfBandsThatAreTooShortOrTooTallToo() {
        assertThat(of(bandOf(2), "textRows")).isEqualTo(2.0 / 24);
        assertThat(of(bandOf(21), "textRows")).isEqualTo(21.0 / 24);
    }

    /** A crop of black, {@code width} x 8, whose rows 0 to 2 each have ink pixels at the given columns. */
    private static double[] rowsWithInkAt(int width, int... columns) {
        Canvas canvas = new Canvas(width, 8, 0);
        for (int y = 0; y < 3; y++) {
            for (int x : columns) {
                canvas.pixel(x, y, 255);
            }
        }
        return CreditsFeatures.features(canvas.build());
    }

    @Test
    void aTextRowNeedsAtLeastOneEdgeOnAnEightPixelWidth() {
        // w - 1 = 7: 50 * e >= 7 needs e >= 1. e = 0 has no ink, e = 1 is one pixel at the border (one edge pair).
        assertThat(of(rowsWithInkAt(8), "bands")).isZero();
        assertThat(of(rowsWithInkAt(8, 0), "bands")).isEqualTo(1);
        assertThat(of(rowsWithInkAt(8, 3), "bands")).isEqualTo(1);
    }

    @Test
    void theLowerTextRowInequalityIsExactAtOneEdgeOnA51PixelWidth() {
        // w - 1 = 50, so 50 * e >= 50 holds with equality at e = 1 and fails at e = 0.
        assertThat(of(rowsWithInkAt(51, 0), "bands")).isEqualTo(1);
        assertThat(of(rowsWithInkAt(51, 0), "textRows")).isEqualTo(3.0 / 8);
        assertThat(of(rowsWithInkAt(51), "bands")).isZero();
        assertThat(of(rowsWithInkAt(51), "textRows")).isZero();
        assertThat(of(rowsWithInkAt(51, 0, 2), "bands")).isEqualTo(1);
    }

    @Test
    void theUpperTextRowInequalityIsExactAtThirtyEdgesOnA51PixelWidth() {
        // 5 * e <= 3 * 50 = 150: equality at e = 30. Isolated pixels at even columns each give two edges, the one at
        // column 0 gives one.
        assertThat(of(rowsWithInkAt(51, evens(0, 28)), "bands")).isEqualTo(1);   // 29 edges
        assertThat(of(rowsWithInkAt(51, evens(2, 30)), "bands")).isEqualTo(1);   // 30 edges
        assertThat(of(rowsWithInkAt(51, evens(0, 30)), "bands")).isZero();       // 31 edges
        assertThat(of(rowsWithInkAt(51, evens(0, 30)), "textRows")).isZero();
    }

    @Test
    void theUpperTextRowInequalityOnAnEightPixelWidthAllowsFourEdgesAndNotFive() {
        // 5 * e <= 21: e = 4 holds (20), e = 5 fails (25).
        assertThat(of(rowsWithInkAt(8, 1, 4), "bands")).isEqualTo(1);
        assertThat(of(rowsWithInkAt(8, 0, 2, 5), "bands")).isZero();
    }

    /** The even columns {@code from, from + 2, ..., to}. */
    private static int[] evens(int from, int to) {
        return java.util.stream.IntStream.rangeClosed(0, (to - from) / 2).map(i -> from + 2 * i).toArray();
    }

    @Test
    void theMedianOfAFrameWithMoreInkThanHalfIsTheInkItself() {
        // 40 of 64 pixels are 255: the median is 255, so the 24 black pixels are the ink.
        GreyFrame crop = new Canvas(8, 8, 255).rect(0, 0, 8, 3, 0).build();

        assertThat(of(CreditsFeatures.features(crop), "ink")).isEqualTo(24.0 / 64);
    }
}
