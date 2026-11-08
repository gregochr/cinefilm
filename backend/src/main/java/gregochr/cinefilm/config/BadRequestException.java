package gregochr.cinefilm.config;

/** Thrown when a request parameter or body is invalid; mapped to a 400 JSON response. */
public class BadRequestException extends RuntimeException {

    /** Creates the exception with the message returned to the client. */
    public BadRequestException(String message) {
        super(message);
    }
}
