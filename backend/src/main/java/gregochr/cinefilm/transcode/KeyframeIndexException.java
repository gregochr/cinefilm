package gregochr.cinefilm.transcode;

/** A file's keyframes could not be read: not an MP4, malformed or truncated boxes, or no usable fallback. */
public class KeyframeIndexException extends RuntimeException {

    /** Creates the exception with a message safe to log (no file contents). */
    public KeyframeIndexException(String message) {
        super(message);
    }

    /** Creates the exception with a message safe to log and the cause. */
    public KeyframeIndexException(String message, Throwable cause) {
        super(message, cause);
    }
}
