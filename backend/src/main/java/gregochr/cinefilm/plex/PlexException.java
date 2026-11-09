package gregochr.cinefilm.plex;

/** Plex could not answer. The message names the call, never the address or the token, so it is safe to log and show. */
public class PlexException extends RuntimeException {

    /** Creates the exception with a message safe to show and an optional cause. */
    public PlexException(String message, Throwable cause) {
        super(message, cause);
    }
}
