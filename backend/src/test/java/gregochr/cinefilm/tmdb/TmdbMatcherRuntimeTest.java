package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link TmdbMatcher#runtimeMatches}: the one runtime rule the scanner and Fix match share. */
class TmdbMatcherRuntimeTest {

    private static final long MIN = 60_000L;

    @Test
    void aFileExactlyEightMinutesFromTheRuntimeStillMatchesInEitherDirection() {
        assertThat(TmdbMatcher.runtimeMatches(100, 108 * MIN)).isTrue();
        assertThat(TmdbMatcher.runtimeMatches(100, 92 * MIN)).isTrue();
    }

    @Test
    void aFileOneMillisecondBeyondEightMinutesDoesNotMatch() {
        assertThat(TmdbMatcher.runtimeMatches(100, 108 * MIN + 1)).isFalse();
        assertThat(TmdbMatcher.runtimeMatches(100, 92 * MIN - 1)).isFalse();
    }

    @Test
    void anUnknownRuntimeOrAnUnknownFileLengthNeverMatches() {
        assertThat(TmdbMatcher.runtimeMatches(null, 100 * MIN)).isFalse();
        assertThat(TmdbMatcher.runtimeMatches(100, 0)).isFalse();
        assertThat(TmdbMatcher.runtimeMatches(100, -1)).isFalse();
    }
}
