package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.transcode.SessionManager;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** When playback needs the machine: an encoder is running, or progress was reported in the last five minutes. */
@ExtendWith(MockitoExtension.class)
class PlaybackGuardTest {

    @Mock
    private SessionManager sessions;

    @Mock
    private PlaybackStateRepository playback;

    private PlaybackGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PlaybackGuard(sessions, playback, CLOCK);
    }

    @Test
    void anEncodingSessionHoldsItBackWithoutAskingForProgress() {
        when(sessions.isEncoding()).thenReturn(true);

        assertThatThrownBy(guard::check).isInstanceOf(PlaybackGuard.Busy.class)
            .hasMessage("a film is being transcoded");
        verifyNoInteractions(playback);
    }

    @Test
    void progressJustInsideFiveMinutesHoldsItBack() {
        when(playback.findLatestLastPlayedAt()).thenReturn(NOW.minus(Duration.ofMinutes(5)).plusMillis(1));

        assertThatThrownBy(guard::check).isInstanceOf(PlaybackGuard.Busy.class)
            .hasMessage("a film was played in the last 5 minutes");
    }

    @Test
    void progressExactlyFiveMinutesAgoNoLongerHoldsItBack() {
        when(playback.findLatestLastPlayedAt()).thenReturn(NOW.minus(Duration.ofMinutes(5)));

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    void noProgressEverMeansNothingIsPlaying() {
        when(playback.findLatestLastPlayedAt()).thenReturn(null);

        assertThatCode(guard::check).doesNotThrowAnyException();
    }

    @Test
    void theQuietPeriodIsFiveMinutes() {
        assertThat(PlaybackGuard.QUIET_MINUTES).isEqualTo(5);
        assertThat(PlaybackGuard.QUIET).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void busyCarriesNoStackTraceBecauseItIsAnExpectedStop() {
        PlaybackGuard.Busy busy = new PlaybackGuard.Busy("waiting");

        assertThat(busy.getStackTrace()).isEmpty();
        assertThat(busy.getCause()).isNull();
        assertThat(busy).hasMessage("waiting");
    }
}
