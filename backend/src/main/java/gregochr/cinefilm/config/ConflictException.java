package gregochr.cinefilm.config;

/** Thrown when a request conflicts with the current state; mapped to a 409 JSON response. */
public class ConflictException extends RuntimeException {

    /** Creates the exception with the message returned to the client. */
    public ConflictException(String message) {
        super(message);
    }
}
