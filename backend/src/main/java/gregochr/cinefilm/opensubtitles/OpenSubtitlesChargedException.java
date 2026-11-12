package gregochr.cinefilm.opensubtitles;

/**
 * A download failed <em>after</em> OpenSubtitles issued its link: the account has already been charged for it, so
 * the download must not be given back to the budget. {@link #permanent()} says whether fetching the same file again
 * could never help (the link was refused, or the file is too big) or might (a timeout, a 5xx on the link). Carries
 * no key, token, URL or cause.
 */
public class OpenSubtitlesChargedException extends OpenSubtitlesUnavailableException {

    private final boolean permanent;

    /**
     * Creates the exception.
     *
     * @param message a message safe to log
     * @param permanent true when the same file can never be fetched
     */
    public OpenSubtitlesChargedException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    /** True when fetching this file again could never help. */
    public boolean permanent() {
        return permanent;
    }
}
