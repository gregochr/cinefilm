package gregochr.cinefilm.transcode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The playbacks Admin has stopped, remembered in memory for {@link #REMEMBER_FOR} (a day, like the longest a quiet
 * session can be rebuilt): long enough for a paused player's next requests, whenever it resumes, to be answered 410
 * instead of being taken for an expired session. Both the session and the viewing
 * (playback id) are remembered, because the session is gone once it is ended and can no longer say which viewing it
 * served. Nothing is persisted: a restart forgets them, and the player then recovers as it does after any restart.
 * Time comes from the injected clock.
 */
@Component
public class StoppedPlaybacks {

    /** How long a stop is remembered. */
    static final Duration REMEMBER_FOR = Duration.ofHours(24);
    /** The most of each kind kept; the eldest is dropped beyond it (Admin is one person pressing Stop). */
    static final int MAX_KEPT = 64;

    private final Clock clock;
    private final Map<String, Instant> sessions = new LinkedHashMap<>();
    private final Map<String, Instant> playbacks = new LinkedHashMap<>();

    /** Creates the record over the application clock. */
    public StoppedPlaybacks(Clock clock) {
        this.clock = clock;
    }

    /**
     * Remembers a stop.
     *
     * @param sessionId the session that was ended
     * @param playbackId the viewing it served, or null when it named none
     */
    public synchronized void record(String sessionId, String playbackId) {
        Instant now = clock.instant();
        prune(now);
        keep(sessions, sessionId, now);
        if (playbackId != null) {
            keep(playbacks, playbackId, now);
        }
    }

    /** Whether this session was stopped from Admin within the last {@link #REMEMBER_FOR}. */
    public synchronized boolean sessionStopped(String sessionId) {
        prune(clock.instant());
        return sessions.containsKey(sessionId);
    }

    /** Whether this viewing was stopped from Admin within the last {@link #REMEMBER_FOR}. */
    public synchronized boolean playbackStopped(String playbackId) {
        prune(clock.instant());
        return playbacks.containsKey(playbackId);
    }

    private static void keep(Map<String, Instant> map, String id, Instant now) {
        map.remove(id);
        map.put(id, now);
        while (map.size() > MAX_KEPT) {
            map.remove(map.keySet().iterator().next());
        }
    }

    private void prune(Instant now) {
        sessions.values().removeIf(at -> !at.plus(REMEMBER_FOR).isAfter(now));
        playbacks.values().removeIf(at -> !at.plus(REMEMBER_FOR).isAfter(now));
    }
}
