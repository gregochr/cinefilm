package gregochr.cinefilm.config;

/**
 * Thrown when a resource was ended on purpose and is not coming back (a playback stopped from Admin); mapped to a 410
 * JSON response, which the player tells apart from an expired session's 404.
 */
public class GoneException extends RuntimeException {

    /** Creates the exception with the message returned to the client. */
    public GoneException(String message) {
        super(message);
    }
}
