package gregochr.cinefilm.subtitle;

/**
 * Published when a pass of the download of forced subtitles added at least one download (it now needs its timing
 * fitted, or a second-opinion comparison). A pass that added nothing publishes nothing.
 */
public record SubtitleDownloadsFinished() {
}
