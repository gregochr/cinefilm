package gregochr.cinefilm.journey;

/**
 * One point of a journey's track: a round of the logger that had a position, with its state. {@code secondsFromStart}
 * counts from the track's first point. {@code courseDeg} and {@code speedMps} are null when the feed gave none.
 */
public record TrackPoint(double latitude, double longitude, Double courseDeg, Double speedMps,
                         long secondsFromStart, PointState state) {

    /** The longest gap, in seconds, between two points that a track segment may join (decision 4). */
    public static final long MAX_JOIN_GAP_S = 60;

    /** Whether a track segment may join the two points: they are at most {@link #MAX_JOIN_GAP_S} seconds apart. */
    public static boolean joins(TrackPoint a, TrackPoint b) {
        return Math.abs(b.secondsFromStart() - a.secondsFromStart()) <= MAX_JOIN_GAP_S;
    }
}
