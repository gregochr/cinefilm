package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoppedPlaybacksTest {

    private static final String SESSION = "5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";
    private static final String PLAYBACK = "c1000000-0000-4000-8000-000000000001";

    private final MutableClock clock = new MutableClock();
    private final StoppedPlaybacks stopped = new StoppedPlaybacks(clock);

    @Test
    void nothingIsStoppedUntilAStopIsRecorded() {
        assertThat(stopped.sessionStopped(SESSION)).isFalse();
        assertThat(stopped.playbackStopped(PLAYBACK)).isFalse();
    }

    @Test
    void aStopRemembersTheSessionAndTheViewingButNotOthers() {
        stopped.record(SESSION, PLAYBACK);

        assertThat(stopped.sessionStopped(SESSION)).isTrue();
        assertThat(stopped.playbackStopped(PLAYBACK)).isTrue();
        assertThat(stopped.sessionStopped(new UUID(0, 1).toString())).isFalse();
        assertThat(stopped.playbackStopped(new UUID(0, 2).toString())).isFalse();
    }

    @Test
    void aStopWithNoViewingRemembersOnlyTheSession() {
        stopped.record(SESSION, null);

        assertThat(stopped.sessionStopped(SESSION)).isTrue();
        assertThat(stopped.playbackStopped(PLAYBACK)).isFalse();
    }

    @Test
    void aStopIsRememberedForADayAndThenForgotten() {
        stopped.record(SESSION, PLAYBACK);

        clock.advance(Duration.ofHours(24).minusMillis(1));
        assertThat(stopped.sessionStopped(SESSION)).isTrue();
        assertThat(stopped.playbackStopped(PLAYBACK)).isTrue();

        clock.advance(Duration.ofMillis(1));
        assertThat(stopped.sessionStopped(SESSION)).isFalse();
        assertThat(stopped.playbackStopped(PLAYBACK)).isFalse();
    }

    @Test
    void stoppingAgainRestartsTheDay() {
        stopped.record(SESSION, PLAYBACK);
        clock.advance(Duration.ofHours(13));
        stopped.record(SESSION, PLAYBACK);
        clock.advance(Duration.ofHours(13));

        assertThat(stopped.sessionStopped(SESSION)).isTrue();
        assertThat(stopped.playbackStopped(PLAYBACK)).isTrue();
    }

    @Test
    void onlyTheNewestStopsAreKeptBeyondTheCap() {
        for (int n = 0; n <= StoppedPlaybacks.MAX_KEPT; n++) {
            stopped.record(new UUID(0, n).toString(), new UUID(1, n).toString());
        }

        assertThat(stopped.sessionStopped(new UUID(0, 0).toString())).isFalse();
        assertThat(stopped.playbackStopped(new UUID(1, 0).toString())).isFalse();
        assertThat(stopped.sessionStopped(new UUID(0, 1).toString())).isTrue();
        assertThat(stopped.playbackStopped(new UUID(1, StoppedPlaybacks.MAX_KEPT).toString())).isTrue();
    }
}
