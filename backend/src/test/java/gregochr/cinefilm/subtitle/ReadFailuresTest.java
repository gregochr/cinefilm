package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The film files whose embedded subtitles could not be read: of which version, and the back-off. */
class ReadFailuresTest {

    private static final Duration WINDOW = Duration.ofHours(6);

    private final ReadFailures failures = new ReadFailures();

    @Test
    void aFailureCountsOnlyForTheVersionOfTheFileThatFailed() {
        failures.record(10L, "1000:1", NOW);

        assertThat(failures.failedFor(10L, "1000:1")).isTrue();
        assertThat(failures.failedFor(10L, "2000:1")).isFalse();
        assertThat(failures.failedFor(11L, "1000:1")).isFalse();
    }

    @Test
    void aFailureHoldsTheReadBackForTheWindowOnly() {
        failures.record(10L, "1000:1", NOW);

        assertThat(failures.backedOff(10L, "1000:1", NOW.plus(WINDOW).minusSeconds(1), WINDOW)).isTrue();
        assertThat(failures.backedOff(10L, "1000:1", NOW.plus(WINDOW), WINDOW)).isFalse();
        assertThat(failures.backedOff(10L, "2000:1", NOW, WINDOW)).isFalse();
        assertThat(failures.backedOff(11L, "1000:1", NOW, WINDOW)).isFalse();
    }

    @Test
    void aReadThatSucceededClearsTheFailure() {
        failures.record(10L, "1000:1", NOW);

        failures.clear(10L);

        assertThat(failures.failedFor(10L, "1000:1")).isFalse();
        assertThat(failures.backedOff(10L, "1000:1", NOW, WINDOW)).isFalse();
    }
}
