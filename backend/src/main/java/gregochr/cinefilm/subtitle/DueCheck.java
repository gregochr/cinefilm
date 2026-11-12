package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.util.List;
import java.util.Set;

/**
 * An edition whose served forced subtitle has not been checked against OpenSubtitles yet (or whose check is stale).
 *
 * @param filmId the film
 * @param editionId the edition
 * @param imdbId the film's IMDb id the candidates were searched for
 * @param editionName the edition's name (its cut tag, such as Extended, orders the candidates)
 * @param candidates the film's single-file OpenSubtitles results, in the order to try them for this edition (the best
 *     fit for the edition's cut first); empty when OpenSubtitles only has multi-part subtitles. A film whose candidates
 *     were never stored is never due
 * @param triedFileIds the file ids this edition has already been compared with for this IMDb id (the cap's ledger)
 * @param servedVersion the version of what the edition serves now (see {@link ServedSubtitle})
 * @param ownerVerified true when the owner has said what is served looks right
 * @param cues what the edition serves, on its timeline
 */
public record DueCheck(Long filmId, Long editionId, String imdbId, String editionName,
                       List<OpenSubtitlesResult> candidates, Set<Long> triedFileIds, String servedVersion,
                       boolean ownerVerified, List<SubtitleCue> cues) {
}
