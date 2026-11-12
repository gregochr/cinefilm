package gregochr.cinefilm.opensubtitles;

/**
 * OpenSubtitles could not answer (outage, rate limit not clearing, a refused key, an unreadable or oversized reply,
 * no key). Never carries the API key or the request URL, and never a cause (whose message could).
 */
public class OpenSubtitlesUnavailableException extends RuntimeException {

    /** Creates the exception with a message safe to log. */
    public OpenSubtitlesUnavailableException(String message) {
        super(message);
    }
}
