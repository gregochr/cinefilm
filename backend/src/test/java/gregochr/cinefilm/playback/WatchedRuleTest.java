package gregochr.cinefilm.playback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WatchedRuleTest {

    private static final long RUNTIME = 100 * 60_000L;

    @Test
    void withoutMarkerThresholdIs92PercentOfRuntime() {
        assertThat(WatchedRule.thresholdMs(RUNTIME, null)).isEqualTo(5_520_000L);
        assertThat(WatchedRule.isWatched(5_519_999L, RUNTIME, null)).isFalse();
        assertThat(WatchedRule.isWatched(5_520_000L, RUNTIME, null)).isTrue();
        assertThat(WatchedRule.isWatched(RUNTIME, RUNTIME, null)).isTrue();
    }

    @Test
    void withMarkerThresholdIsTheMarkerNotNinetyTwoPercent() {
        long marker = 80 * 60_000L;
        assertThat(WatchedRule.thresholdMs(RUNTIME, marker)).isEqualTo(marker);
        assertThat(WatchedRule.isWatched(marker - 1, RUNTIME, marker)).isFalse();
        assertThat(WatchedRule.isWatched(marker, RUNTIME, marker)).isTrue();
    }

    @Test
    void markerLaterThanNinetyTwoPercentStillWins() {
        long marker = 97 * 60_000L;
        assertThat(WatchedRule.isWatched(95 * 60_000L, RUNTIME, marker)).isFalse();
    }

    @Test
    void zeroMarkerIsTreatedAsNoMarker() {
        assertThat(WatchedRule.thresholdMs(RUNTIME, 0L)).isEqualTo(5_520_000L);
    }

    @Test
    void unknownRuntimeAndNoMarkerIsNeverWatched() {
        assertThat(WatchedRule.thresholdMs(0, null)).isEqualTo(-1);
        assertThat(WatchedRule.isWatched(1_000_000L, 0, null)).isFalse();
    }

    @Test
    void unknownRuntimeButMarkerStillWorks() {
        assertThat(WatchedRule.isWatched(500L, 0, 400L)).isTrue();
    }
}
