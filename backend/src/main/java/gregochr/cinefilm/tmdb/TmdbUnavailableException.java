package gregochr.cinefilm.tmdb;

/** TMDB could not answer (outage, rate limit not clearing, bad token, no token). Never carries the token. */
public class TmdbUnavailableException extends RuntimeException {

    /** Creates the exception with a message safe to log and an optional cause. */
    public TmdbUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
