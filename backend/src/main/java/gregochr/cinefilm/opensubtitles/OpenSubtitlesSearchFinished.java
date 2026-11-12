package gregochr.cinefilm.opensubtitles;

/**
 * Published when a pass of the OpenSubtitles search has ended (whether it searched every due film or stopped on an
 * outage): films may have a new best result to check the owner's subtitle files against.
 */
public record OpenSubtitlesSearchFinished() {
}
