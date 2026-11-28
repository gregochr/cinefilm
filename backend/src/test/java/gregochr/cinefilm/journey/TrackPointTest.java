package gregochr.cinefilm.journey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrackPointTest {

    private static TrackPoint at(long seconds) {
        return new TrackPoint(0.5, -20.0, null, null, seconds, PointState.GOOD);
    }

    @Test
    void pointsSixtySecondsApartJoinAndSixtyOneDoNot() {
        assertThat(TrackPoint.joins(at(100), at(160))).isTrue();
        assertThat(TrackPoint.joins(at(100), at(161))).isFalse();
    }

    @Test
    void theOrderOfThePointsDoesNotMatter() {
        assertThat(TrackPoint.joins(at(160), at(100))).isTrue();
        assertThat(TrackPoint.joins(at(161), at(100))).isFalse();
    }
}
