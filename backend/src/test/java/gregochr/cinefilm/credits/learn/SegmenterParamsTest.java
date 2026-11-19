package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SegmenterParamsTest {

    @Test
    void theNumbersAreKept() {
        SegmenterParams p = new SegmenterParams(1.5, 2.5, 3, -4.5, 0.8, 0.7);

        assertThat(p.lambda()).isEqualTo(1.5);
        assertThat(p.delta()).isEqualTo(2.5);
        assertThat(p.gapFrames()).isEqualTo(3);
        assertThat(p.sigma()).isEqualTo(-4.5);
        assertThat(p.theta()).isEqualTo(0.8);
        assertThat(p.thetaDense()).isEqualTo(0.7);
    }

    @Test
    void theEdgesOfEachRangeAreAccepted() {
        assertThatCode(() -> new SegmenterParams(0, 0, 1, -1e9, 0, 0)).doesNotThrowAnyException();
        assertThatCode(() -> new SegmenterParams(1e9, 1e9, 1, 1e9, 1, 1)).doesNotThrowAnyException();
    }

    @Test
    void aNegativeLambdaIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(-1e-9, 0, 1, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("lambda must be finite and at least 0: -1.0E-9");
    }

    @Test
    void aNonFiniteLambdaIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(Double.POSITIVE_INFINITY, 0, 1, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lambda");
        assertThatThrownBy(() -> new SegmenterParams(Double.NaN, 0, 1, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lambda");
    }

    @Test
    void aNegativeOrNonFiniteDeltaIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(0, -1e-9, 1, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("delta must be finite and at least 0: -1.0E-9");
        assertThatThrownBy(() -> new SegmenterParams(0, Double.POSITIVE_INFINITY, 1, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delta");
        assertThatThrownBy(() -> new SegmenterParams(0, Double.NaN, 1, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delta");
    }

    @Test
    void noGapFramesIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 0, 0, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("gapFrames must be at least 1: 0");
    }

    @Test
    void aNonFiniteSigmaIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, Double.NaN, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("sigma must be finite: NaN");
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, Double.NEGATIVE_INFINITY, 0.5, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sigma");
    }

    @Test
    void aThetaOutsideZeroToOneIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, 0, -1e-9, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("theta must be in [0, 1]: -1.0E-9");
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, 0, Math.nextUp(1.0), 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("theta must");
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, 0, Double.NaN, 0.5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("theta must");
    }

    @Test
    void aThetaDenseOutsideZeroToOneIsRefused() {
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, 0, 0.5, -1e-9))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("thetaDense must be in [0, 1]: -1.0E-9");
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, 0, 0.5, Math.nextUp(1.0)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thetaDense");
        assertThatThrownBy(() -> new SegmenterParams(0, 0, 1, 0, 0.5, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("thetaDense");
    }
}
