package gregochr.cinefilm.subtitle;

/**
 * Published when a pass of the check of forced subtitles against OpenSubtitles ended and saved at least one result (a
 * new verdict may have made an owner's file fail, so a download may be due). A pass that saved nothing publishes
 * nothing, so the chain of checks, downloads and timing ends instead of going round forever.
 */
public record SidecarChecksFinished() {
}
