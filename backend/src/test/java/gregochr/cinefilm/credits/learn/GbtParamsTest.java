package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GbtParamsTest {

    @Test
    void theDefaultsAreThePlans() {
        GbtParams p = GbtParams.defaults();

        assertThat(p.trees()).isEqualTo(200);
        assertThat(p.depth()).isEqualTo(3);
        assertThat(p.learningRate()).isEqualTo(0.1);
        assertThat(p.bins()).isEqualTo(32);
        assertThat(p.minLeaf()).isEqualTo(20);
        assertThat(p.lambda()).isEqualTo(1.0);
    }

    @Test
    void theEdgesOfEachRangeAreAccepted() {
        assertThatCode(() -> new GbtParams(1, 1, 1.0, 2, 1, 0.0)).doesNotThrowAnyException();
        assertThatCode(() -> new GbtParams(1, 1, Double.MIN_VALUE, 256, 1, 5.0)).doesNotThrowAnyException();
    }

    @Test
    void noTreesIsRefused() {
        assertThatThrownBy(() -> new GbtParams(0, 3, 0.1, 32, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trees");
    }

    @Test
    void depthZeroIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 0, 0.1, 32, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("depth");
    }

    @Test
    void aLearningRateOfZeroIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.0, 32, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("learningRate");
    }

    @Test
    void aLearningRateAboveOneIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 1.0001, 32, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("learningRate");
    }

    @Test
    void aNaNLearningRateIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, Double.NaN, 32, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("learningRate");
    }

    @Test
    void oneBinIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.1, 1, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bins");
    }

    @Test
    void moreThan256BinsIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.1, 257, 20, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bins");
    }

    @Test
    void aMinLeafOfZeroIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.1, 32, 0, 1.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minLeaf");
    }

    @Test
    void aNegativeLambdaIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.1, 32, 20, -0.5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lambda");
    }

    @Test
    void aNaNLambdaIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.1, 32, 20, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lambda");
    }

    @Test
    void anInfiniteLambdaIsRefused() {
        assertThatThrownBy(() -> new GbtParams(1, 1, 0.1, 32, 20, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lambda");
    }
}
