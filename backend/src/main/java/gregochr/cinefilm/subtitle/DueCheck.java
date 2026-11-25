package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.util.List;
import java.util.Set;

/**
 * A forced subtitle of an edition that has not been checked against OpenSubtitles yet (or whose check is stale): the
 * owner's file(s) as playback would serve them, or one of the edition's downloads.
 *
 * @param filmId the film
 * @param editionId the edition
 * @param imdbId the film's IMDb id the candidates were searched for
 * @param editionName the edition's name (its cut tag, such as Extended, orders the candidates)
 * @param candidates the film's single-file OpenSubtitles results, in the order to try them for this edition (the best
 *     fit for the edition's cut first); empty when OpenSubtitles only has multi-part subtitles. A film whose candidates
 *     were never stored is never due. For a download its own file is never among them (a file cannot be the second
 *     opinion on itself)
 * @param triedFileIds the file ids this edition has already been compared with for this IMDb id (the cap's ledger)
 * @param servedVersion the version of what is checked now (see {@link ServedSubtitle})
 * @param ownerVerified true when the owner has said what is checked looks right
 * @param cues what is checked, on the edition's timeline
 * @param downloadSidecarId the downloaded subtitle that is checked, or null when it is the owner's file(s)
 * @param side what is checked, for the rate rule ({@link RateComparison}); the owner's file(s) when null
 */
public record DueCheck(Long filmId, Long editionId, String imdbId, String editionName,
                       List<OpenSubtitlesResult> candidates, Set<Long> triedFileIds, String servedVersion,
                       boolean ownerVerified, List<SubtitleCue> cues, Long downloadSidecarId,
                       RateComparison.Side side) {

    /** A check of a download, with nothing known of its rate (as before the rate rule). */
    public DueCheck(Long filmId, Long editionId, String imdbId, String editionName,
                    List<OpenSubtitlesResult> candidates, Set<Long> triedFileIds, String servedVersion,
                    boolean ownerVerified, List<SubtitleCue> cues, Long downloadSidecarId) {
        this(filmId, editionId, imdbId, editionName, candidates, triedFileIds, servedVersion, ownerVerified, cues,
            downloadSidecarId, null);
    }

    /** A check of the owner's file(s). */
    public DueCheck(Long filmId, Long editionId, String imdbId, String editionName,
                    List<OpenSubtitlesResult> candidates, Set<Long> triedFileIds, String servedVersion,
                    boolean ownerVerified, List<SubtitleCue> cues) {
        this(filmId, editionId, imdbId, editionName, candidates, triedFileIds, servedVersion, ownerVerified, cues,
            null, null);
    }

    /** What is checked, as the rate rule sees it: the owner's file(s) unless a download was given. */
    public RateComparison.Side rateSide() {
        return side == null ? RateComparison.Side.file() : side;
    }

    /** True when a downloaded subtitle is checked, not the owner's file(s). */
    public boolean isDownload() {
        return downloadSidecarId != null;
    }
}
