package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecondsTest {

    @Test
    void formatsWholeAndFractionalSecondsWithThreeDecimals() {
        assertThat(Seconds.format(0)).isEqualTo("0.000");
        assertThat(Seconds.format(5)).isEqualTo("0.005");
        assertThat(Seconds.format(999)).isEqualTo("0.999");
        assertThat(Seconds.format(1000)).isEqualTo("1.000");
        assertThat(Seconds.format(4004)).isEqualTo("4.004");
        assertThat(Seconds.format(7_200_123)).isEqualTo("7200.123");
    }
}
