package gregochr.cinefilm.subtitle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The film files whose embedded subtitles could not be read (ffprobe missing, an error, a timeout), of which version
 * of the file, and when. The timing check leaves such a file alone for a while ({@link #backedOff}); the retime job
 * asks whether a file has failed ({@link #failedFor}) so it does not wait for a fit that cannot come. A changed file
 * is not held back by an old failure. The count is in memory only and is cleared by a successful read.
 */
@Component
public class ReadFailures {

    /** How many reads of the same version of a file must fail before the file counts as unreadable. */
    static final int GIVE_UP = 3;

    /** Reads that failed: of which version of the file, when the last one did, and how many in a row. */
    private record Failure(String fingerprint, Instant at, int count) {
    }

    private final Map<Long, Failure> failed = new ConcurrentHashMap<>();

    /** Notes that a file, as it is now (its fingerprint), could not be read. */
    public void record(Long mediaFileId, String fingerprint, Instant at) {
        failed.merge(mediaFileId, new Failure(fingerprint, at, 1), (old, now) ->
            new Failure(fingerprint, at, old.fingerprint().equals(fingerprint) ? old.count() + 1 : 1));
    }

    /** Forgets a failure: the file was read. */
    public void clear(Long mediaFileId) {
        failed.remove(mediaFileId);
    }

    /**
     * True when reads of the file, as it is now, have failed {@value #GIVE_UP} times in a row: one ffprobe timeout or
     * a share briefly unmounted does not make it unreadable.
     */
    public boolean failedFor(Long mediaFileId, String fingerprint) {
        Failure failure = failed.get(mediaFileId);
        return failure != null && failure.fingerprint().equals(fingerprint) && failure.count() >= GIVE_UP;
    }

    /** True when the file, as it is now, failed less than {@code window} ago: it is not read again yet. */
    public boolean backedOff(Long mediaFileId, String fingerprint, Instant now, Duration window) {
        Failure failure = failed.get(mediaFileId);
        return failure != null && failure.fingerprint().equals(fingerprint)
            && now.isBefore(failure.at().plus(window));
    }
}
