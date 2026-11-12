package gregochr.cinefilm.subtitle;

/**
 * Published when the owner removed a downloaded subtitle in Admin: the edition may now be due for the next candidate.
 *
 * @param editionId the edition the download belonged to
 */
public record SubtitleDownloadRemoved(Long editionId) {
}
