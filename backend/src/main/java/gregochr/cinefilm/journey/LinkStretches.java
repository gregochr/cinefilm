package gregochr.cinefilm.journey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decision 6 of the train look-ahead plan, from tracks alone (no database, no clock).
 *
 * <p>A stretch is a run of bad points along a track, with at most one good or unknown point between two bad ones;
 * a gap between points too long for a track segment (see {@link TrackPoint#joins}) ends a run. Runs of any tracks
 * that come within {@value #OVERLAP_M} m of each other are merged. Each track then carries the merged stretch
 * wherever it lies on it, in its own seconds: its points within {@value #OVERLAP_M} m of the merged extent.
 *
 * <p>Duration is the longest any track took through the whole extent. A track went through the whole extent only
 * when it has a joined point before and after the extent and its path there comes within {@value #OVERLAP_M} m of
 * every point of the extent. With no such track, it is the longest path any track has inside the extent at the
 * slowest speed of {@value #MIN_MOVING_MPS} m/s or more recorded inside it; with no such speed the stretch is left
 * out. Need is the duration x {@value #GROWTH} + {@value #MARGIN_S} s.
 */
final class LinkStretches {

    /** Runs closer than this, in metres, are one stretch; a track point this close to the extent is on it. */
    static final double OVERLAP_M = 300;
    /** The lowest speed, in metres per second, that counts as moving. */
    static final double MIN_MOVING_MPS = 5;
    static final double GROWTH = 1.25;
    static final long MARGIN_S = 60;

    private static final double EARTH_RADIUS_M = 6_371_000;
    /** Absorbs floating-point error so a distance of exactly {@link #OVERLAP_M} counts as within it. */
    private static final double EPSILON_M = 1e-6;

    private LinkStretches() {
    }

    /** A run of points {@code first} to {@code last} (both included) of track number {@code track}. */
    private record Run(int track, int first, int last) {
    }

    /** The tracks as the map: their points and the merged stretches on each. Tracks of under two points are dropped. */
    static List<LinkMap.Track> build(List<List<TrackPoint>> all) {
        List<List<TrackPoint>> tracks = all.stream().filter(t -> t.size() >= 2).toList();
        List<Run> bad = new ArrayList<>();
        for (int t = 0; t < tracks.size(); t++) {
            bad.addAll(badRuns(t, tracks.get(t)));
        }
        List<List<LinkMap.Stretch>> stretches = new ArrayList<>();
        tracks.forEach(t -> stretches.add(new ArrayList<>()));
        for (List<Run> members : merge(tracks, bad)) {
            List<Run> on = onRuns(tracks, members);
            Double need = need(tracks, members, on);
            if (need != null) {
                for (Run run : on) {
                    List<TrackPoint> points = tracks.get(run.track());
                    stretches.get(run.track()).add(new LinkMap.Stretch(points.get(run.first()).secondsFromStart(),
                        points.get(run.last()).secondsFromStart(), need));
                }
            }
        }
        List<LinkMap.Track> map = new ArrayList<>();
        for (int t = 0; t < tracks.size(); t++) {
            List<LinkMap.Point> points = tracks.get(t).stream().map(p -> new LinkMap.Point(p.latitude(),
                p.longitude(), p.courseDeg(), p.secondsFromStart())).toList();
            List<LinkMap.Stretch> sorted = stretches.get(t).stream()
                .sorted(Comparator.comparingLong(LinkMap.Stretch::from).thenComparingLong(LinkMap.Stretch::to))
                .toList();
            map.add(new LinkMap.Track(points, sorted));
        }
        return map;
    }

    /** The runs of bad points of one track: two good or unknown points in a row, or a gap, end a run. */
    private static List<Run> badRuns(int track, List<TrackPoint> points) {
        List<Run> runs = new ArrayList<>();
        int first = -1;
        int last = -1;
        for (int i = 0; i < points.size(); i++) {
            if (first >= 0 && (i - last > 2 || !TrackPoint.joins(points.get(i - 1), points.get(i)))) {
                runs.add(new Run(track, first, last));
                first = -1;
            }
            if (points.get(i).state() == PointState.BAD) {
                first = first < 0 ? i : first;
                last = i;
            }
        }
        if (first >= 0) {
            runs.add(new Run(track, first, last));
        }
        return runs;
    }

    /** The runs grouped into stretches that overlap (directly or through others), in order of first appearance. */
    private static List<List<Run>> merge(List<List<TrackPoint>> tracks, List<Run> bad) {
        List<List<Run>> groups = new ArrayList<>();
        boolean[] taken = new boolean[bad.size()];
        for (int seed = 0; seed < bad.size(); seed++) {
            if (!taken[seed]) {
                List<Run> group = new ArrayList<>();
                taken[seed] = true;
                group.add(bad.get(seed));
                for (int g = 0; g < group.size(); g++) {
                    for (int other = 0; other < bad.size(); other++) {
                        if (!taken[other] && overlap(tracks, group.get(g), bad.get(other))) {
                            taken[other] = true;
                            group.add(bad.get(other));
                        }
                    }
                }
                groups.add(group);
            }
        }
        return groups;
    }

    private static boolean overlap(List<List<TrackPoint>> tracks, Run a, Run b) {
        return anyPointNear(tracks, a, b) || anyPointNear(tracks, b, a);
    }

    private static boolean anyPointNear(List<List<TrackPoint>> tracks, Run run, Run line) {
        for (int i = run.first(); i <= run.last(); i++) {
            if (near(tracks.get(run.track()).get(i), tracks.get(line.track()), line.first(), line.last())) {
                return true;
            }
        }
        return false;
    }

    /** The runs of points, per track, that lie on the merged extent: within the overlap distance of a member. */
    private static List<Run> onRuns(List<List<TrackPoint>> tracks, List<Run> members) {
        List<Run> on = new ArrayList<>();
        for (int t = 0; t < tracks.size(); t++) {
            List<TrackPoint> points = tracks.get(t);
            int first = -1;
            int last = -1;
            for (int i = 0; i < points.size(); i++) {
                if (first >= 0 && !TrackPoint.joins(points.get(i - 1), points.get(i))) {
                    on.add(new Run(t, first, last));
                    first = -1;
                }
                if (nearAny(tracks, points.get(i), members)) {
                    first = first < 0 ? i : first;
                    last = i;
                } else if (first >= 0) {
                    on.add(new Run(t, first, last));
                    first = -1;
                }
            }
            if (first >= 0) {
                on.add(new Run(t, first, last));
            }
        }
        return on;
    }

    private static boolean nearAny(List<List<TrackPoint>> tracks, TrackPoint p, List<Run> members) {
        return members.stream().anyMatch(m -> near(p, tracks.get(m.track()), m.first(), m.last()));
    }

    /** The need of a merged stretch, or null when its duration cannot be known. */
    private static Double need(List<List<TrackPoint>> tracks, List<Run> members, List<Run> on) {
        double duration = -1;
        for (Run run : on) {
            if (wentThrough(tracks, members, run)) {
                List<TrackPoint> points = tracks.get(run.track());
                duration = Math.max(duration,
                    points.get(run.last()).secondsFromStart() - points.get(run.first()).secondsFromStart());
            }
        }
        if (duration < 0) {
            Double speed = slowestMoving(tracks, on);
            if (speed == null) {
                return null;
            }
            duration = longestPath(tracks, on) / speed;
        }
        return duration * GROWTH + MARGIN_S;
    }

    /** Whether the track, on this run, came in after a joined point and left before another, covering all of it. */
    private static boolean wentThrough(List<List<TrackPoint>> tracks, List<Run> members, Run run) {
        List<TrackPoint> points = tracks.get(run.track());
        if (run.first() == 0 || run.last() == points.size() - 1
            || !TrackPoint.joins(points.get(run.first() - 1), points.get(run.first()))
            || !TrackPoint.joins(points.get(run.last()), points.get(run.last() + 1))) {
            return false;
        }
        for (Run member : members) {
            for (int i = member.first(); i <= member.last(); i++) {
                if (!near(tracks.get(member.track()).get(i), points, run.first() - 1, run.last() + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The lowest speed of {@link #MIN_MOVING_MPS} or more at a point on the extent, or null when there is none. */
    private static Double slowestMoving(List<List<TrackPoint>> tracks, List<Run> on) {
        Double slowest = null;
        for (Run run : on) {
            for (int i = run.first(); i <= run.last(); i++) {
                Double speed = tracks.get(run.track()).get(i).speedMps();
                if (speed != null && speed >= MIN_MOVING_MPS && (slowest == null || speed < slowest)) {
                    slowest = speed;
                }
            }
        }
        return slowest;
    }

    /** The longest path, in metres, that a track has along the extent. */
    private static double longestPath(List<List<TrackPoint>> tracks, List<Run> on) {
        double longest = 0;
        for (Run run : on) {
            List<TrackPoint> points = tracks.get(run.track());
            double length = 0;
            for (int i = run.first(); i < run.last(); i++) {
                length += distanceM(points.get(i + 1), points.get(i), points.get(i));
            }
            longest = Math.max(longest, length);
        }
        return longest;
    }

    /** Whether the point is within the overlap distance of the path through points {@code from} to {@code to}. */
    private static boolean near(TrackPoint p, List<TrackPoint> path, int from, int to) {
        for (int i = from; i < Math.max(to, from + 1); i++) {
            if (distanceM(p, path.get(i), path.get(Math.min(i + 1, to))) <= OVERLAP_M + EPSILON_M) {
                return true;
            }
        }
        return false;
    }

    /** Metres from {@code p} to the segment {@code a} to {@code b}, flat around {@code p}. */
    private static double distanceM(TrackPoint p, TrackPoint a, TrackPoint b) {
        double cosLat = Math.cos(Math.toRadians(p.latitude()));
        double ax = east(a, p, cosLat);
        double ay = north(a, p);
        double dx = east(b, p, cosLat) - ax;
        double dy = north(b, p) - ay;
        double length2 = dx * dx + dy * dy;
        double f = length2 == 0 ? 0 : Math.min(1, Math.max(0, -(ax * dx + ay * dy) / length2));
        return Math.hypot(ax + f * dx, ay + f * dy);
    }

    private static double east(TrackPoint q, TrackPoint origin, double cosLat) {
        return Math.toRadians(q.longitude() - origin.longitude()) * EARTH_RADIUS_M * cosLat;
    }

    private static double north(TrackPoint q, TrackPoint origin) {
        return Math.toRadians(q.latitude() - origin.latitude()) * EARTH_RADIUS_M;
    }
}
