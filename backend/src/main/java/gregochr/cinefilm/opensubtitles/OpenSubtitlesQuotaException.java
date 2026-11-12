package gregochr.cinefilm.opensubtitles;

import java.time.Instant;

/**
 * OpenSubtitles said the download allowance is used up. It is an outage for anything that does not know about quotas
 * (it extends {@link OpenSubtitlesUnavailableException}); the download run stops on it until the reset time.
 */
public class OpenSubtitlesQuotaException extends OpenSubtitlesUnavailableException {

    private final transient Instant resetTimeUtc;

    /**
     * Creates the exception.
     *
     * @param resetTimeUtc when the allowance comes back, or null when OpenSubtitles did not say
     */
    public OpenSubtitlesQuotaException(Instant resetTimeUtc) {
        super("OpenSubtitles download limit reached");
        this.resetTimeUtc = resetTimeUtc;
    }

    /** When the allowance comes back, or null when OpenSubtitles did not say. */
    public Instant resetTimeUtc() {
        return resetTimeUtc;
    }
}
