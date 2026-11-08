package gregochr.cinefilm.library;

/** ffprobe could not read a file, timed out, or produced output that is not usable. */
public class ProbeException extends RuntimeException {

    /** Creates the exception with a message safe to log (no file contents) and an optional cause. */
    public ProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}
