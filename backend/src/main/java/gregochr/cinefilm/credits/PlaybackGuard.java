package gregochr.cinefilm.credits;

import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.transcode.SessionManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Says when playback needs the machine, so the background reads of the film files (credits detection, the credits
 * dataset job) stand aside. Playback counts as going while any HLS session is encoding or while progress was reported
 * in the last {@value #QUIET_MINUTES} minutes (direct play reads over SMB too).
 */
@Component
public class PlaybackGuard {

    /** How long after the last progress report playback still counts as going, in minutes. */
    public static final int QUIET_MINUTES = 5;
    /** How long after the last progress report playback still counts as going. */
    public static final Duration QUIET = Duration.ofMinutes(QUIET_MINUTES);

    private final SessionManager sessions;
    private final PlaybackStateRepository playback;
    private final Clock clock;

    /** Why a background read must stop: playback needs the machine. Not an error, and it has no stack trace. */
    public static final class Busy extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with the reason, as it is logged. */
        public Busy(String message) {
            super(message, null, false, false);
        }
    }

    /** Creates the guard. */
    public PlaybackGuard(SessionManager sessions, PlaybackStateRepository playback, Clock clock) {
        this.sessions = sessions;
        this.playback = playback;
        this.clock = clock;
    }

    /**
     * Returns normally while nothing is playing.
     *
     * @throws Busy when an encoder is running, or progress was reported in the last {@value #QUIET_MINUTES} minutes
     */
    public void check() {
        if (sessions.isEncoding()) {
            throw new Busy("a film is being transcoded");
        }
        Instant lastProgress = playback.findLatestLastPlayedAt();
        if (lastProgress != null && clock.instant().isBefore(lastProgress.plus(QUIET))) {
            throw new Busy("a film was played in the last " + QUIET_MINUTES + " minutes");
        }
    }
}
