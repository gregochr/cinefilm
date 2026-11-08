package gregochr.cinefilm.playback;

/**
 * The watched rule (decision 1): an edition is watched once playback reaches its credits marker, or 92% of its
 * runtime when there is no marker.
 */
public final class WatchedRule {

    /** Fraction of the runtime that counts as watched when no credits marker is known. */
    public static final double FALLBACK_FRACTION = 0.92;

    private WatchedRule() {
    }

    /** The position at which an edition becomes watched, or -1 if it can never be decided. */
    public static long thresholdMs(long runtimeMs, Long creditsMarkerMs) {
        if (creditsMarkerMs != null && creditsMarkerMs > 0) {
            return creditsMarkerMs;
        }
        if (runtimeMs <= 0) {
            return -1;
        }
        return (long) Math.ceil(runtimeMs * FALLBACK_FRACTION);
    }

    /** Whether a playback position counts as having watched the edition. */
    public static boolean isWatched(long positionMs, long runtimeMs, Long creditsMarkerMs) {
        long threshold = thresholdMs(runtimeMs, creditsMarkerMs);
        return threshold >= 0 && positionMs >= threshold;
    }
}
