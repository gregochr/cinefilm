package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The factor k at every band edge, on both sides of each. */
class FrameRateRetimeTest {

    private static final double TOLERANCE = 1e-12;

    @ParameterizedTest
    @ValueSource(doubles = {24.85, 24.887, 24.98, 25.0, 25.001, 25.15})
    void aPalFilmTakesA23976SubtitleAs23976Over25(double film) {
        assertThat(FrameRateRetime.factor(23.976, film)).isCloseTo(23.976 / 25, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(23.98, film)).isCloseTo(23.976 / 25, within(TOLERANCE));
    }

    @Test
    void theStatedRate23976BandRunsFrom23966To23986() {
        assertThat(FrameRateRetime.factor(23.966, 25.0)).isCloseTo(23.976 / 25, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(23.986, 25.0)).isCloseTo(23.976 / 25, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(23.965, 25.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(23.9, 25.0)).isEqualTo(1.0);
    }

    @Test
    void theStatedRate24Band() {
        assertThat(FrameRateRetime.factor(24.0, 25.0)).isCloseTo(24.0 / 25, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(23.99, 25.0)).isCloseTo(24.0 / 25, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(24.01, 25.0)).isCloseTo(24.0 / 25, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(23.987, 25.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(24.02, 25.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(24.1, 25.0)).isEqualTo(1.0);
    }

    @Test
    void theFilmBandOf25Ends() {
        assertThat(FrameRateRetime.factor(23.976, 24.84)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(23.976, 25.16)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(24.0, 24.84)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(24.0, 25.16)).isEqualTo(1.0);
    }

    @Test
    void aFilmOfAbout24TakesA25SubtitleAs25Over23976Below23988AndOver24FromThere() {
        assertThat(FrameRateRetime.factor(25.0, 23.9)).isCloseTo(25 / 23.976, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(25.0, 23.976)).isCloseTo(25 / 23.976, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(25.0, 23.987)).isCloseTo(25 / 23.976, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(25.0, 23.988)).isCloseTo(25 / 24.0, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(25.0, 24.0)).isCloseTo(25 / 24.0, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(25.0, 24.1)).isCloseTo(25 / 24.0, within(TOLERANCE));
    }

    @Test
    void theFilmBandOf24Ends() {
        assertThat(FrameRateRetime.factor(25.0, 23.89)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(25.0, 24.11)).isEqualTo(1.0);
    }

    @Test
    void theStatedRate25BandRunsFrom2499To2501() {
        assertThat(FrameRateRetime.factor(24.99, 24.0)).isCloseTo(25 / 24.0, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(25.01, 24.0)).isCloseTo(25 / 24.0, within(TOLERANCE));
        assertThat(FrameRateRetime.factor(24.98, 24.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(25.02, 24.0)).isEqualTo(1.0);
    }

    @Test
    void anUnknownRateOnEitherSideGivesOne() {
        assertThat(FrameRateRetime.factor(null, 25.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(23.976, null)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(null, null)).isEqualTo(1.0);
    }

    @Test
    void matchingRatesAndOtherCopiesGiveOne() {
        assertThat(FrameRateRetime.factor(25.0, 25.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(23.976, 23.976)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(24.0, 23.976)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(23.976, 30.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(25.0, 30.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(30.0, 25.0)).isEqualTo(1.0);
        assertThat(FrameRateRetime.factor(29.97, 23.976)).isEqualTo(1.0);
    }

    @Test
    void twoRatesAreTheSameRateOnlyWhenBothAreKnownAndWithin001() {
        assertThat(FrameRateRetime.sameRate(23.976, 23.98)).isTrue();
        assertThat(FrameRateRetime.sameRate(23.976, 24.0)).isFalse();
        assertThat(FrameRateRetime.sameRate(null, 25.0)).isFalse();
        assertThat(FrameRateRetime.sameRate(25.0, null)).isFalse();
    }
}
