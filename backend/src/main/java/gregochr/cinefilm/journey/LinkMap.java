package gregochr.cinefilm.journey;

import java.util.List;

/**
 * The map of past journeys' link quality that the player matches a position against (train-lookahead-plan.md,
 * decision 2). It carries only what matching needs: no journey id, date, time, train or modem. {@code t}, {@code from}
 * and {@code to} are seconds from the start of the track they belong to.
 */
public record LinkMap(List<Track> tracks) {

    /** One journey's track: its points in time order and the stretches that lie on it. */
    public record Track(List<Point> points, List<Stretch> stretches) {
    }

    /** A track point; {@code courseDeg} is null when the feed gave none. */
    public record Point(double latitude, double longitude, Double courseDeg, long t) {
    }

    /** A bad stretch of the line, in a track's own seconds, and {@code need}: seconds of film to hold ahead for it. */
    public record Stretch(long from, long to, double need) {
    }
}
