package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import java.util.OptionalInt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The most OpenSubtitles downloads CineFilm makes in a day: the account's own allowance (read from the login reply,
 * so a VIP upgrade needs no change) capped by {@code cinefilm.opensubtitles.daily-download-limit}. While the allowance
 * is not known it is taken to be {@value #FREE_ALLOWANCE}, a free account's, the safe guess. The shares of
 * {@link DownloadShares} are taken from this number. Asked at the start of every run, so it follows the account.
 */
@Component
public class DailyDownloadLimit {

    /** What a free OpenSubtitles account is allowed a day. */
    static final int FREE_ALLOWANCE = 20;

    private final OpenSubtitlesApi api;
    private final int cap;

    /** Creates the limit; {@code cap} is the owner's own ceiling (the default is a VIP account's 1000). */
    public DailyDownloadLimit(OpenSubtitlesApi api,
                              @Value("${cinefilm.opensubtitles.daily-download-limit:1000}") int cap) {
        this.api = api;
        this.cap = cap;
    }

    /**
     * Today's limit.
     *
     * @return the account's allowance, or {@value #FREE_ALLOWANCE} when it is not known, and never more than the cap
     */
    public int today() {
        OptionalInt allowance = api.allowedDownloads();
        return Math.min(cap, allowance.orElse(FREE_ALLOWANCE));
    }
}
