package gregochr.cinefilm.config;

/** Thrown when something CineFilm depends on (TMDB) cannot answer; mapped to a 503 JSON response. */
public class ServiceUnavailableException extends RuntimeException {

    /** Creates the exception with the message returned to the client. */
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
