package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import org.junit.jupiter.api.Test;

class FrameStatsTest {

    private static GreyFrame grey(int width, int height, int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new GreyFrame(width, height, bytes);
    }

    @Test
    void aTwentyPixelFrameHasHandComputedMeanPercentilesAndShares() {
        // Sorted: 0 0 10 34 | 35 35 50 100 100 100 | 150 150 200 200 | 201 201 255 255 255 255, shuffled below.
        // Sum 2586, so the mean is 129.3. Rank 1 (5%) is 0; rank 10 (50%) ends the run of 100s, a tie at the
        // boundary that must stay at 100, not 150; rank 19 (95%) is 255. Dark is below 35: 0 0 10 34 (4 of 20). Bright
        // is above 200: 201 201 and four 255 (6 of 20); the two 35s and the two 200s are neither.
        GreyFrame frame = grey(4, 5, 255, 100, 35, 201, 0, 150, 255, 200, 100, 50, 255, 34, 200, 100, 0, 150, 255,
            35, 201, 10);

        FrameStats.Grey stats = FrameStats.grey(frame);

        assertThat(stats.mean()).isEqualTo(129.3, within(1e-9));
        assertThat(stats.p5()).isZero();
        assertThat(stats.p50()).isEqualTo(100);
        assertThat(stats.p95()).isEqualTo(255);
        assertThat(stats.darkShare()).isEqualTo(0.2, within(1e-12));
        assertThat(stats.brightShare()).isEqualTo(0.3, within(1e-12));
    }

    @Test
    void theNearestRankOfThreePixelsRoundsUp() {
        // Ranks ceil(0.15) = 1, ceil(1.5) = 2 and ceil(2.85) = 3.
        FrameStats.Grey stats = FrameStats.grey(grey(3, 1, 30, 10, 20));

        assertThat(stats.p5()).isEqualTo(10);
        assertThat(stats.p50()).isEqualTo(20);
        assertThat(stats.p95()).isEqualTo(30);
    }

    @Test
    void aSinglePixelIsEveryPercentile() {
        FrameStats.Grey stats = FrameStats.grey(grey(1, 1, 77));

        assertThat(stats.mean()).isEqualTo(77.0);
        assertThat(stats.p5()).isEqualTo(77);
        assertThat(stats.p50()).isEqualTo(77);
        assertThat(stats.p95()).isEqualTo(77);
        assertThat(stats.darkShare()).isZero();
        assertThat(stats.brightShare()).isZero();
    }

    @Test
    void thirtyFiveIsNotDarkAndTwoHundredIsNotBrightButTheNeighboursAre() {
        assertThat(FrameStats.grey(grey(2, 1, 34, 35)).darkShare()).isEqualTo(0.5);
        assertThat(FrameStats.grey(grey(2, 1, 200, 201)).brightShare()).isEqualTo(0.5);
        assertThat(FrameStats.grey(grey(2, 1, 35, 200)).darkShare()).isZero();
        assertThat(FrameStats.grey(grey(2, 1, 35, 200)).brightShare()).isZero();
    }

    @Test
    void aColourFrameHasHandComputedChannelMeansAndChroma() {
        // Pixels (255,0,0) (0,255,0) (0,0,255) (10,20,30): R 265/4, G 275/4, B 285/4; chroma 255 255 255 20 = 785/4.
        byte[] bytes = {(byte) 255, 0, 0, 0, (byte) 255, 0, 0, 0, (byte) 255, 10, 20, 30};

        FrameStats.Colour stats = FrameStats.colour(new RgbFrame(2, 2, bytes));

        assertThat(stats.meanR()).isEqualTo(66.25);
        assertThat(stats.meanG()).isEqualTo(68.75);
        assertThat(stats.meanB()).isEqualTo(71.25);
        assertThat(stats.chroma()).isEqualTo(196.25);
    }

    @Test
    void aGreyColourFrameHasNoChroma() {
        FrameStats.Colour stats = FrameStats.colour(MeasureFixtures.colour(3, 90));

        assertThat(stats.meanR()).isEqualTo(90.0);
        assertThat(stats.meanG()).isEqualTo(90.0);
        assertThat(stats.meanB()).isEqualTo(90.0);
        assertThat(stats.chroma()).isZero();
    }

    @Test
    void chromaTakesTheLargestMinusTheSmallestOfAnyOrder() {
        // (30,200,90): 170. (200,30,90): 170. (90,30,200): 170. Mean 170.
        byte[] bytes = {30, (byte) 200, 90, (byte) 200, 30, 90, 90, 30, (byte) 200};

        assertThat(FrameStats.colour(new RgbFrame(3, 1, bytes)).chroma()).isEqualTo(170.0);
    }
}
