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
 * is not held back by an old failure. In memory only: a restart tries again.
 */
@Component
public class ReadFailures {

    /** A read that failed: of which version of the file, and when. */
    private record Failure(String fingerprint, Instant at) {
    }

    private final Map<Long, Failure> failed = new ConcurrentHashMap<>();

    /** Notes that a file, as it is now (its fingerprint), could not be read. */
    public void record(Long mediaFileId, String fingerprint, Instant at) {
        failed.put(mediaFileId, new Failure(fingerprint, at));
    }

    /** Forgets a failure: the file was read. */
    public void clear(Long mediaFileId) {
        failed.remove(mediaFileId);
    }

    /** True when the file, as it is now, has failed to be read (however long ago). */
    public boolean failedFor(Long mediaFileId, String fingerprint) {
        Failure failure = failed.get(mediaFileId);
        return failure != null && failure.fingerprint().equals(fingerprint);
    }

    /** True when the file, as it is now, failed less than {@code window} ago: it is not read again yet. */
    public boolean backedOff(Long mediaFileId, String fingerprint, Instant now, Duration window) {
        Failure failure = failed.get(mediaFileId);
        return failure != null && failure.fingerprint().equals(fingerprint)
            && now.isBefore(failure.at().plus(window));
    }
}
