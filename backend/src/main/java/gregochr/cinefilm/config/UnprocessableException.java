package gregochr.cinefilm.config;

/** Thrown when a request was understood and allowed but its outcome cannot be used; mapped to a 422 JSON response. */
public class UnprocessableException extends RuntimeException {

    /** Creates the exception with the message returned to the client. */
    public UnprocessableException(String message) {
        super(message);
    }
}
