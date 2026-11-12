package gregochr.cinefilm.subtitle;

import java.util.List;

/**
 * An edition whose served forced subtitle has not been checked against OpenSubtitles yet (or whose check is stale).
 *
 * @param filmId the film
 * @param editionId the edition
 * @param imdbId the film's IMDb id the best result was searched for
 * @param osFileId the file id of the film's best OpenSubtitles result
 * @param osRelease that result's release name, or null
 * @param osFileCount how many files that result is split over (above 1: only part of the film)
 * @param servedVersion the version of what the edition serves now (see {@link ServedSubtitle})
 * @param ownerVerified true when the owner has said what is served looks right
 * @param cues what the edition serves, on its timeline
 */
public record DueCheck(Long filmId, Long editionId, String imdbId, long osFileId, String osRelease,
                       int osFileCount, String servedVersion, boolean ownerVerified, List<SubtitleCue> cues) {
}
