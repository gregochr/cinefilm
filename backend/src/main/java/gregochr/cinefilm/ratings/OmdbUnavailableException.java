package gregochr.cinefilm.ratings;

/** OMDb could not answer (outage, unreadable reply, no key). Never carries the API key or the request URL. */
public class OmdbUnavailableException extends RuntimeException {

    /** Creates the exception with a message safe to log. */
    public OmdbUnavailableException(String message) {
        super(message);
    }
}
