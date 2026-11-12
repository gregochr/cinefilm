package gregochr.cinefilm.subtitle;

/**
 * Published when a pass of the timing check ended and fitted at least one subtitle (a downloaded subtitle whose timing
 * could not be proven is then due for the second-opinion comparison with OpenSubtitles). A pass that fitted nothing
 * publishes nothing, so the chain of checks, downloads and timing ends instead of going round forever.
 */
public record SidecarTimingFinished() {
}
