package gregochr.cinefilm.transcode;

/** The session's encoder failed twice; it cannot make more segments (503). */
public class SessionUnavailableException extends RuntimeException {

    /** Creates the exception. */
    public SessionUnavailableException() {
        super("Transcoding failed for this session");
    }
}
