package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LrParamsTest {

    @Test
    void theDefaultsAreThePlans() {
        LrParams p = LrParams.defaults();

        assertThat(p.l2()).isEqualTo(1.0);
        assertThat(p.maxIterations()).isEqualTo(300);
        assertThat(p.tolerance()).isEqualTo(1e-6);
    }

    @Test
    void theEdgesOfEachRangeAreAccepted() {
        assertThatCode(() -> new LrParams(Double.MIN_VALUE, 1, Double.MIN_VALUE)).doesNotThrowAnyException();
        assertThatCode(() -> new LrParams(Double.MAX_VALUE, 10_000, Double.MAX_VALUE)).doesNotThrowAnyException();
    }

    @Test
    void l2OfZeroIsRefused() {
        assertThatThrownBy(() -> new LrParams(0, 300, 1e-6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("l2 must be finite and above 0: 0.0");
    }

    @Test
    void anInfiniteL2IsRefused() {
        assertThatThrownBy(() -> new LrParams(Double.POSITIVE_INFINITY, 300, 1e-6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("l2 must be finite and above 0: Infinity");
    }

    @Test
    void aNaNL2IsRefused() {
        assertThatThrownBy(() -> new LrParams(Double.NaN, 300, 1e-6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("l2 must be finite and above 0: NaN");
    }

    @Test
    void aNegativeL2IsRefused() {
        assertThatThrownBy(() -> new LrParams(-1, 300, 1e-6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("l2 must be finite and above 0: -1.0");
    }

    @Test
    void zeroIterationsIsRefused() {
        assertThatThrownBy(() -> new LrParams(1, 0, 1e-6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxIterations must be 1 to 10000: 0");
    }

    @Test
    void moreThanTenThousandIterationsIsRefused() {
        assertThatThrownBy(() -> new LrParams(1, 10_001, 1e-6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxIterations must be 1 to 10000: 10001");
    }

    @Test
    void aToleranceOfZeroIsRefused() {
        assertThatThrownBy(() -> new LrParams(1, 300, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tolerance must be finite and above 0: 0.0");
    }

    @Test
    void aNaNToleranceIsRefused() {
        assertThatThrownBy(() -> new LrParams(1, 300, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tolerance must be finite and above 0: NaN");
    }

    @Test
    void anInfiniteToleranceIsRefused() {
        assertThatThrownBy(() -> new LrParams(1, 300, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tolerance must be finite and above 0: Infinity");
    }
}
