package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.credits.learn.FeatureFixtures.negative;
import static gregochr.cinefilm.credits.learn.FeatureFixtures.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.learn.FeatureFixtures.Canvas;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The two properties of {@link CreditsFeatures}, checked on examples. */
class CreditsFeaturesPropertyTest {

    /** The four statistics that change under a negative; every other feature must not. */
    private static final List<String> LUMA_STATISTICS = List.of("mean", "p10", "p50", "p90");

    // 9 x 9 = 81 pixels. B = 128. Ink above (255, 192) and below (0, 64) the median, exactly 64 away at 192 and 64;
    // 140 is plain, 115 is neither plain nor ink.
    private static final GreyFrame NINE = FeatureFixtures.rows(
        "..#.#h...",
        ".##..l.o.",
        "....p..o.",
        "oo.#..hh.",
        "..q..q...",
        "#...ll...",
        ".h..p..#.",
        "..oo..#h.",
        "l...#....");

    private static final GreyFrame NINE_BEFORE = FeatureFixtures.rows(
        ".........",
        ".#......o",
        "..hh.....",
        "....o....",
        ".l....q..",
        "......#..",
        "p........",
        "...#.....",
        "........h");

    private static final GreyFrame NINE_AFTER = FeatureFixtures.rows(
        "#........",
        "...h.....",
        ".....oo..",
        "..l......",
        "......q.#",
        "..#......",
        ".........",
        ".p.....h.",
        "o........");

    private static GreyFrame thirtyOneByTwentyThree(int shift) {
        Canvas canvas = new Canvas(31, 23, 120);
        canvas.text(2 + shift, 2, 4, 6, 255).text(2 + shift, 9, 4, 6, 0).text(2 + shift, 17, 3, 5, 190);
        canvas.rect(0, 0, 3, 23, 60).rect(28, 5, 3, 4, 200).pixel(15 + shift, 14, 56).pixel(16, 14, 184);
        return canvas.build();
    }

    private static void assertNegativeGivesTheSameFeaturesExceptLumaStatistics(
        GreyFrame crop, GreyFrame previous, GreyFrame next) {
        double[] features = CreditsFeatures.features(crop, previous, next);
        double[] negated = CreditsFeatures.features(negative(crop), negative(previous), negative(next));

        assertThat(crop.width() * crop.height() % 2).isEqualTo(1);
        for (int i = 0; i < CreditsFeatures.COUNT; i++) {
            String name = CreditsFeatures.COLUMNS.get(i);
            if (!LUMA_STATISTICS.contains(name)) {
                assertThat(negated[i]).as(name).isEqualTo(features[i]);
            }
        }
        // The statistics themselves are the mirror image: the mean and the median exactly (odd pixel count).
        assertThat(of(negated, "mean")).isCloseTo(255 - of(features, "mean"), within(1e-9));
        assertThat(of(negated, "p50")).isEqualTo(255 - of(features, "p50"));
        // The fixture has ink on both sides of the median, so the property is not vacuous.
        assertThat(of(features, "ink")).isPositive();
    }

    @Test
    void aNineByNineFrameAndItsNegativeGiveEqualFeaturesButTheLumaStatistics() {
        assertNegativeGivesTheSameFeaturesExceptLumaStatistics(NINE, NINE_BEFORE, NINE_AFTER);
    }

    @Test
    void aThirtyOneByTwentyThreeFrameAndItsNegativeGiveEqualFeaturesButTheLumaStatistics() {
        assertNegativeGivesTheSameFeaturesExceptLumaStatistics(
            thirtyOneByTwentyThree(0), thirtyOneByTwentyThree(1), thirtyOneByTwentyThree(2));
    }

    @Test
    void theNineByNineFixtureHasInkOnBothSidesOfTheMedianAndTheBoundaryValues() {
        double[] features = CreditsFeatures.features(NINE);

        assertThat(of(features, "p50")).isEqualTo(128);
        // 255, 192, 0 and 64 are all ink at exactly 64 or more from 128; 140 and 115 are not.
        long ink = NINE.width() * NINE.height() - countOf(NINE, 128) - countOf(NINE, 140) - countOf(NINE, 115);
        assertThat(of(features, "ink")).isEqualTo(ink / 81.0);
    }

    private static long countOf(GreyFrame frame, int value) {
        long count = 0;
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                if (frame.luma(x, y) == value) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void aFrameComparedWithItselfHasNoChange() {
        for (GreyFrame crop : List.of(NINE, thirtyOneByTwentyThree(0), FeatureFixtures.uniform(8, 8, 90),
            new Canvas(32, 24, 0).text(4, 3, 4, 6, 255).build())) {
            double[] features = CreditsFeatures.features(crop, crop, crop);

            assertThat(of(features, "mad")).isZero();
            assertThat(of(features, "inkChange")).isZero();
        }
    }

    @Test
    void aFrameComparedWithItselfOnOneSideOnlyHasHalfTheChangeOfTheOther() {
        double[] both = CreditsFeatures.features(NINE, NINE_BEFORE, NINE_BEFORE);
        double[] one = CreditsFeatures.features(NINE, NINE_BEFORE, NINE);

        assertThat(of(one, "mad")).isEqualTo(of(both, "mad") / 2);
        assertThat(of(one, "inkChange")).isEqualTo(of(both, "inkChange") / 2);
        assertThat(of(both, "mad")).isPositive();
        assertThat(of(both, "inkChange")).isPositive();
    }
}
