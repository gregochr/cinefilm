package gregochr.cinefilm.transcode;

/** The encoder could not be started for a new session. */
public class SessionStartException extends RuntimeException {

    /** Creates the exception with the cause. */
    public SessionStartException(Throwable cause) {
        super("The encoder could not be started", cause);
    }
}
