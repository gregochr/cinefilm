package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import java.util.List;

/**
 * An edition that is due a downloaded subtitle: a forced one, or (kind {@link Kind#FULL}) a full English one.
 *
 * @param kind {@link Kind#FULL}, {@link Kind#MISSING} or {@link Kind#REPLACEMENT}
 * @param filmId the film
 * @param editionId the edition
 * @param imdbId the film's IMDb id the candidates were searched for
 * @param editionName the edition's name
 * @param promotion a reference OpenSubtitles file already downloaded for this edition that fits (a replacement only):
 *     it costs no download, so it is used before anything is fetched; null when there is none
 * @param candidates the files to try fetching, best first (none of them blocked, already downloads, already compared
 *     with the edition or already known to be unusable)
 * @param remainingFetches how many more files this edition may ever be given ({@link DownloadPlan#MAX_DOWNLOADS})
 * @param fileVersion for a replacement, the served version of the owner's file that failed (the add is dropped when it
 *     changed meanwhile, for example the owner verified it); null for a missing one
 */
public record DueDownload(Kind kind, Long filmId, Long editionId, String imdbId, String editionName,
                          OpenSubtitlesReference promotion, List<OpenSubtitlesResult> candidates, int remainingFetches,
                          String fileVersion) {
}
