package gregochr.cinefilm.subtitle;

/**
 * How the day's OpenSubtitles download budget is shared (decision 42). One budget serves three jobs, in priority
 * order: downloads of missing subtitles, downloads of replacements for owner files that failed every check, and the
 * comparisons of the owner's files and of downloads with a second OpenSubtitles file. The jobs run in that order when
 * they run together (missing before replacements), and each has a ceiling on the day's total used, so a lower job can
 * never starve a higher one:
 * <ul>
 *   <li>missing: the whole limit;</li>
 *   <li>replacements: up to 70% of the limit, so 30% is always left for missing ones;</li>
 *   <li>comparisons: up to 50% of the limit, so half is always left for downloads.</li>
 * </ul>
 * The budget itself ({@code OpenSubtitlesBudget.take}) enforces the ceilings in one conditional statement; no refund is
 * given once OpenSubtitles has charged. All in exact integer arithmetic (a limit of 90 gives 63 and 45). Pure.
 */
public final class DownloadShares {

    /** The share of the limit the replacements may bring the day's total to, in percent. */
    static final int REPLACEMENT_PERCENT = 70;
    /** The share of the limit the comparisons may bring the day's total to, in percent. */
    static final int CHECK_PERCENT = 50;

    private DownloadShares() {
    }

    /**
     * The ceiling for downloading a missing subtitle.
     *
     * @param limit the day's limit
     * @return the limit itself
     */
    public static int forMissing(int limit) {
        return limit;
    }

    /**
     * The ceiling for downloading a replacement of an owner's file that failed every check.
     *
     * @param limit the day's limit
     * @return {@value #REPLACEMENT_PERCENT}% of it, rounded down
     */
    public static int forReplacements(int limit) {
        return Math.max(0, limit) * REPLACEMENT_PERCENT / 100;
    }

    /**
     * The ceiling for downloading a second OpenSubtitles file to compare with.
     *
     * @param limit the day's limit
     * @return {@value #CHECK_PERCENT}% of it, rounded down
     */
    public static int forChecks(int limit) {
        return Math.max(0, limit) * CHECK_PERCENT / 100;
    }
}
