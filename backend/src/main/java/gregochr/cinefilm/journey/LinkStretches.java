package gregochr.cinefilm.journey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;
import java.util.function.LongConsumer;

/**
 * Decision 6 of the train look-ahead plan, from tracks alone (no database, no clock).
 *
 * <p>A stretch is a run of bad points along a track, with at most one good or unknown point between two bad ones;
 * a gap between points too long for a track segment (see {@link TrackPoint#joins}) ends a run. Runs of any tracks
 * that come within {@value #OVERLAP_M} m of each other are merged. A track lies on the merged extent where its
 * points are within {@value #OVERLAP_M} m of it, and where a joined segment, whose two points are not, passes
 * within {@value #OVERLAP_M} m of it (a crossing: its entry and exit are interpolated along the segment by
 * distance, for the map only).
 *
 * <p>Duration is the longest any track took through the whole extent, from the joined point just before its run on
 * the extent to the joined point just after it (a crossing: the two points of its segments). A track went through
 * the whole extent only when it has such points and its path between them comes within {@value #OVERLAP_M} m of
 * every point of the extent. With no such track, it is the longest path any track has among the points on the
 * extent at the slowest speed of {@value #MIN_MOVING_MPS} m/s or more recorded at them; with no such speed the
 * stretch is left out. Need is the duration x {@value #GROWTH} + {@value #MARGIN_S} s.
 *
 * <p>Every "within {@value #OVERLAP_M} m" question goes through a grid of cells over a flat projection, so the work
 * grows with the points near a stretch, not with all points times all stretches. The grid only finds candidates; the
 * answer is the exact distance, flat around the point asked about.
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
    private static final double LIMIT_M = OVERLAP_M + EPSILON_M;
    private static final double CELL_M = 400;
    /** Steps of the searches along a segment (a third is lost per ternary step, a half per bisection). */
    private static final int SEARCH_STEPS = 60;
    private static final double SECONDS_EPSILON = 1e-6;
    private static final BitSet NO_POINTS = new BitSet();

    private record Bad(int track, int first, int last) {
    }

    /**
     * A run of a track on a merged extent: points {@code first} to {@code last}; a crossing is the joined segments
     * from point {@code first} to point {@code last}, none of whose points is on the extent. {@code from} and
     * {@code to} are the seconds of the stretch on the track.
     */
    private record Run(int track, int first, int last, boolean crossing, long from, long to) {
    }

    /** A growing list of ints. */
    private static final class IntList {
        private int[] values = new int[4];
        private int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }
    }

    private final List<List<TrackPoint>> tracks;
    private final double[][] x;
    private final double[][] y;
    private final double[][] cos;
    private final double margin;
    private final List<Bad> bad = new ArrayList<>();
    /** Elements of the bad runs: {track, point a, point b, bad run}; a single-point run is a segment of no length. */
    private final List<int[]> elements = new ArrayList<>();
    private final Map<Long, IntList> cells = new HashMap<>();
    private final IntList found = new IntList();
    private int[] stamp = new int[0];
    private int round;
    private int[] compOf;
    private final List<List<Integer>> members = new ArrayList<>();
    private final List<Map<Integer, BitSet>> on = new ArrayList<>();
    private final List<Map<Integer, TreeMap<Integer, double[]>>> crossings = new ArrayList<>();

    private LinkStretches(List<List<TrackPoint>> tracks) {
        this.tracks = tracks;
        double meanLat = tracks.stream().flatMap(List::stream).mapToDouble(TrackPoint::latitude).average().orElse(0);
        double cosRef = Math.cos(Math.toRadians(meanLat));
        double cosMin = tracks.stream().flatMap(List::stream)
            .mapToDouble(p -> Math.cos(Math.toRadians(p.latitude()))).min().orElse(1);
        // A flat grid in metres at the mean latitude: far from it, a true distance of 300 m reads up to this much more.
        this.margin = LIMIT_M * 1.01 * Math.max(1, cosRef / cosMin);
        this.x = new double[tracks.size()][];
        this.y = new double[tracks.size()][];
        this.cos = new double[tracks.size()][];
        for (int t = 0; t < tracks.size(); t++) {
            List<TrackPoint> points = tracks.get(t);
            x[t] = points.stream().mapToDouble(p -> Math.toRadians(p.longitude()) * EARTH_RADIUS_M * cosRef).toArray();
            y[t] = points.stream().mapToDouble(p -> Math.toRadians(p.latitude()) * EARTH_RADIUS_M).toArray();
            cos[t] = points.stream().mapToDouble(p -> Math.cos(Math.toRadians(p.latitude()))).toArray();
        }
    }

    /** The tracks as the map: their points and the merged stretches on each. Tracks of under two points are dropped. */
    static List<LinkMap.Track> build(List<List<TrackPoint>> all) {
        return new LinkStretches(all.stream().filter(t -> t.size() >= 2).toList()).run();
    }

    private List<LinkMap.Track> run() {
        for (int t = 0; t < tracks.size(); t++) {
            findBadRuns(t);
        }
        indexBadRuns();
        merge();
        markPoints();
        markCrossings();
        List<List<LinkMap.Stretch>> stretches = new ArrayList<>();
        tracks.forEach(t -> stretches.add(new ArrayList<>()));
        for (int c = 0; c < members.size(); c++) {
            List<Run> runs = runsOf(c);
            Double need = need(c, runs);
            if (need != null) {
                for (Run run : runs) {
                    stretches.get(run.track()).add(new LinkMap.Stretch(run.from(), run.to(), need));
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
    private void findBadRuns(int track) {
        List<TrackPoint> points = tracks.get(track);
        int first = -1;
        int last = -1;
        for (int i = 0; i < points.size(); i++) {
            if (first >= 0 && (i - last > 2 || !TrackPoint.joins(points.get(i - 1), points.get(i)))) {
                bad.add(new Bad(track, first, last));
                first = -1;
            }
            if (points.get(i).state() == PointState.BAD) {
                first = first < 0 ? i : first;
                last = i;
            }
        }
        if (first >= 0) {
            bad.add(new Bad(track, first, last));
        }
    }

    private void indexBadRuns() {
        for (int r = 0; r < bad.size(); r++) {
            Bad run = bad.get(r);
            if (run.first() == run.last()) {
                addElement(run.track(), run.first(), run.first(), r);
            }
            for (int i = run.first(); i < run.last(); i++) {
                addElement(run.track(), i, i + 1, r);
            }
        }
        stamp = new int[elements.size()];
    }

    private void addElement(int track, int a, int b, int owner) {
        int id = elements.size();
        elements.add(new int[] {track, a, b, owner});
        forCells(Math.min(x[track][a], x[track][b]), Math.min(y[track][a], y[track][b]),
            Math.max(x[track][a], x[track][b]), Math.max(y[track][a], y[track][b]),
            key -> cells.computeIfAbsent(key, k -> new IntList()).add(id));
    }

    private static void forCells(double minX, double minY, double maxX, double maxY, LongConsumer action) {
        long x1 = (long) Math.floor(maxX / CELL_M);
        long y1 = (long) Math.floor(maxY / CELL_M);
        for (long cx = (long) Math.floor(minX / CELL_M); cx <= x1; cx++) {
            for (long cy = (long) Math.floor(minY / CELL_M); cy <= y1; cy++) {
                action.accept((cx << 32) ^ (cy & 0xFFFFFFFFL));
            }
        }
    }

    /** The ids of the elements that may lie within the overlap distance of the box (valid until the next call). */
    private IntList near(double minX, double minY, double maxX, double maxY) {
        found.size = 0;
        round++;
        forCells(minX - margin, minY - margin, maxX + margin, maxY + margin, key -> {
            IntList ids = cells.get(key);
            if (ids != null) {
                for (int k = 0; k < ids.size; k++) {
                    int id = ids.values[k];
                    if (stamp[id] != round) {
                        stamp[id] = round;
                        found.add(id);
                    }
                }
            }
        });
        return found;
    }

    /** Groups the bad runs into stretches that overlap, directly or through others, in order of first appearance. */
    private void merge() {
        int[] parent = new int[bad.size()];
        for (int r = 0; r < parent.length; r++) {
            parent[r] = r;
        }
        for (int r = 0; r < bad.size(); r++) {
            Bad run = bad.get(r);
            for (int i = run.first(); i <= run.last(); i++) {
                IntList ids = near(x[run.track()][i], y[run.track()][i], x[run.track()][i], y[run.track()][i]);
                for (int k = 0; k < ids.size; k++) {
                    int[] element = elements.get(ids.values[k]);
                    int other = root(parent, element[3]);
                    if (other != root(parent, r) && distance(run.track(), i, element) <= LIMIT_M) {
                        parent[other] = root(parent, r);
                    }
                }
            }
        }
        compOf = new int[bad.size()];
        Map<Integer, Integer> indexOfRoot = new HashMap<>();
        for (int r = 0; r < bad.size(); r++) {
            int component = indexOfRoot.computeIfAbsent(root(parent, r), k -> indexOfRoot.size());
            compOf[r] = component;
            if (component == members.size()) {
                members.add(new ArrayList<>());
                on.add(new HashMap<>());
                crossings.add(new HashMap<>());
            }
            members.get(component).add(r);
        }
    }

    private static int root(int[] parent, int node) {
        int top = node;
        while (parent[top] != top) {
            top = parent[top];
        }
        return top;
    }

    /** Marks, per merged stretch, the points of every track that lie within the overlap distance of its extent. */
    private void markPoints() {
        for (int t = 0; t < tracks.size(); t++) {
            for (int i = 0; i < tracks.get(t).size(); i++) {
                IntList ids = near(x[t][i], y[t][i], x[t][i], y[t][i]);
                for (int k = 0; k < ids.size; k++) {
                    int[] element = elements.get(ids.values[k]);
                    int track = t;
                    BitSet bits = on.get(compOf[element[3]]).computeIfAbsent(track, key -> new BitSet());
                    // Once the point is known to be on, the rest of this stretch's elements need not be measured.
                    if (!bits.get(i) && distance(t, i, element) <= LIMIT_M) {
                        bits.set(i);
                    }
                }
            }
        }
    }

    /** Marks the joined segments, with no point on the extent, that pass within the overlap distance of it. */
    private void markCrossings() {
        for (int t = 0; t < tracks.size(); t++) {
            List<TrackPoint> points = tracks.get(t);
            for (int i = 0; i + 1 < points.size(); i++) {
                if (TrackPoint.joins(points.get(i), points.get(i + 1))) {
                    IntList ids = near(Math.min(x[t][i], x[t][i + 1]), Math.min(y[t][i], y[t][i + 1]),
                        Math.max(x[t][i], x[t][i + 1]), Math.max(y[t][i], y[t][i + 1]));
                    for (int k = 0; k < ids.size; k++) {
                        int[] element = elements.get(ids.values[k]);
                        int c = compOf[element[3]];
                        BitSet bits = on.get(c).getOrDefault(t, NO_POINTS);
                        if (!bits.get(i) && !bits.get(i + 1)) {
                            markCrossing(c, t, i, element);
                        }
                    }
                }
            }
        }
    }

    private void markCrossing(int c, int t, int i, int[] element) {
        List<TrackPoint> other = tracks.get(element[0]);
        double[] interval = clip(tracks.get(t).get(i), tracks.get(t).get(i + 1), other.get(element[1]),
            other.get(element[2]));
        if (interval != null) {
            double[] hull = crossings.get(c).computeIfAbsent(t, key -> new TreeMap<>())
                .computeIfAbsent(i, key -> new double[] {1, 0});
            hull[0] = Math.min(hull[0], interval[0]);
            hull[1] = Math.max(hull[1], interval[1]);
        }
    }

    /** The runs of every track on a merged stretch: runs of points on it and crossings of it, in track order. */
    private List<Run> runsOf(int c) {
        List<Run> runs = new ArrayList<>();
        SortedSet<Integer> trackIds = new TreeSet<>(on.get(c).keySet());
        trackIds.addAll(crossings.get(c).keySet());
        for (int t : trackIds) {
            List<TrackPoint> points = tracks.get(t);
            BitSet bits = on.get(c).getOrDefault(t, NO_POINTS);
            for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(runEnd(points, bits, i) + 1)) {
                int last = runEnd(points, bits, i);
                runs.add(new Run(t, i, last, false, points.get(i).secondsFromStart(),
                    points.get(last).secondsFromStart()));
            }
            List<Integer> segments = new ArrayList<>(crossings.get(c).getOrDefault(t, new TreeMap<>()).keySet());
            for (int k = 0; k < segments.size(); k++) {
                int e = k;
                while (e + 1 < segments.size() && segments.get(e + 1) == segments.get(e) + 1) {
                    e++;
                }
                runs.add(crossing(c, t, segments.get(k), segments.get(e)));
                k = e;
            }
        }
        return runs;
    }

    /** The last point of the run of on points from {@code first}: the next is on and joined to it, or not. */
    private static int runEnd(List<TrackPoint> points, BitSet bits, int first) {
        int last = first;
        while (bits.get(last + 1) && TrackPoint.joins(points.get(last), points.get(last + 1))) {
            last++;
        }
        return last;
    }

    private Run crossing(int c, int t, int firstSegment, int lastSegment) {
        List<TrackPoint> points = tracks.get(t);
        double[] entry = crossings.get(c).get(t).get(firstSegment);
        double[] exit = crossings.get(c).get(t).get(lastSegment);
        double in = lerp(points.get(firstSegment), points.get(firstSegment + 1), entry[0]);
        double out = lerp(points.get(lastSegment), points.get(lastSegment + 1), exit[1]);
        return new Run(t, firstSegment, lastSegment + 1, true, (long) Math.floor(in + SECONDS_EPSILON),
            (long) Math.ceil(out - SECONDS_EPSILON));
    }

    private static double lerp(TrackPoint a, TrackPoint b, double fraction) {
        return a.secondsFromStart() + fraction * (b.secondsFromStart() - a.secondsFromStart());
    }

    /** The need of a merged stretch, or null when its duration cannot be known. */
    private Double need(int c, List<Run> runs) {
        double duration = -1;
        for (Run run : runs) {
            if (wentThrough(c, run)) {
                duration = Math.max(duration, span(run));
            }
        }
        if (duration < 0) {
            Double speed = slowestMoving(runs);
            if (speed == null) {
                return null;
            }
            duration = longestPath(runs) / speed;
        }
        return duration * GROWTH + MARGIN_S;
    }

    private long span(Run run) {
        List<TrackPoint> points = tracks.get(run.track());
        return run.crossing()
            ? points.get(run.last()).secondsFromStart() - points.get(run.first()).secondsFromStart()
            : points.get(run.last() + 1).secondsFromStart() - points.get(run.first() - 1).secondsFromStart();
    }

    /** Whether the track, on this run, came in after a joined point and left before another, covering all of it. */
    private boolean wentThrough(int c, Run run) {
        List<TrackPoint> points = tracks.get(run.track());
        if (!run.crossing() && (run.first() == 0 || run.last() == points.size() - 1
            || !TrackPoint.joins(points.get(run.first() - 1), points.get(run.first()))
            || !TrackPoint.joins(points.get(run.last()), points.get(run.last() + 1)))) {
            return false;
        }
        int from = run.crossing() ? run.first() : run.first() - 1;
        int to = run.crossing() ? run.last() : run.last() + 1;
        int[] hint = {0};
        for (int r : members.get(c)) {
            Bad member = bad.get(r);
            for (int i = member.first(); i <= member.last(); i++) {
                if (!near(tracks.get(member.track()).get(i), cos[member.track()][i], points, from, to, hint)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The lowest speed of {@link #MIN_MOVING_MPS} or more at a point on the extent, or null when there is none. */
    private Double slowestMoving(List<Run> runs) {
        Double slowest = null;
        for (Run run : runs) {
            for (int i = run.first(); !run.crossing() && i <= run.last(); i++) {
                Double speed = tracks.get(run.track()).get(i).speedMps();
                if (speed != null && speed >= MIN_MOVING_MPS && (slowest == null || speed < slowest)) {
                    slowest = speed;
                }
            }
        }
        return slowest;
    }

    /** The longest path, in metres, that a track has along the points on the extent. */
    private double longestPath(List<Run> runs) {
        double longest = 0;
        for (Run run : runs) {
            List<TrackPoint> points = tracks.get(run.track());
            double length = 0;
            for (int i = run.first(); !run.crossing() && i < run.last(); i++) {
                length += distanceM(points.get(i + 1), points.get(i), points.get(i));
            }
            longest = Math.max(longest, length);
        }
        return longest;
    }

    /** Metres from the track point to an element (a segment of a bad run). */
    private double distance(int track, int point, int[] element) {
        List<TrackPoint> other = tracks.get(element[0]);
        return distanceM(tracks.get(track).get(point), cos[track][point], other.get(element[1]),
            other.get(element[2]));
    }

    /**
     * Whether the point is within the overlap distance of the path through points {@code from} to {@code to}. The
     * {@code hint} is the segment where the last answer was found, tried first, as a run's points follow the line.
     */
    private static boolean near(TrackPoint p, double cosLat, List<TrackPoint> path, int from, int to,
                                int[] hint) {
        int segments = Math.max(to - from, 1);
        for (int k = 0; k < segments; k++) {
            int i = from + (hint[0] + k) % segments;
            if (distanceM(p, cosLat, path.get(i), path.get(Math.min(i + 1, to))) <= LIMIT_M) {
                hint[0] = i - from;
                return true;
            }
        }
        return false;
    }

    private static double distanceM(TrackPoint p, TrackPoint a, TrackPoint b) {
        return distanceM(p, Math.cos(Math.toRadians(p.latitude())), a, b);
    }

    /** Metres from {@code p} to the segment {@code a} to {@code b}, flat around {@code p}. */
    private static double distanceM(TrackPoint p, double cosLat, TrackPoint a, TrackPoint b) {
        return flat(0, 0, east(a, p, cosLat), north(a, p), east(b, p, cosLat), north(b, p));
    }

    /** Metres from (px, py) to the segment (ax, ay) to (bx, by). */
    private static double flat(double px, double py, double ax, double ay, double bx, double by) {
        double rx = ax - px;
        double ry = ay - py;
        double dx = bx - ax;
        double dy = by - ay;
        double length2 = dx * dx + dy * dy;
        double f = length2 == 0 ? 0 : Math.min(1, Math.max(0, -(rx * dx + ry * dy) / length2));
        return Math.hypot(rx + f * dx, ry + f * dy);
    }

    /**
     * Where along the segment {@code a} to {@code b} (as fractions of its length) it is within the overlap distance
     * of the segment {@code c} to {@code d}, from the first such fraction to the last; null when it never is. The
     * distance along a straight segment is convex, so a ternary search finds its nearest point and a bisection on
     * each side the two ends.
     */
    private static double[] clip(TrackPoint a, TrackPoint b, TrackPoint c, TrackPoint d) {
        double cosLat = Math.cos(Math.toRadians(a.latitude()));
        double bx = east(b, a, cosLat);
        double by = north(b, a);
        double cx = east(c, a, cosLat);
        double cy = north(c, a);
        double dx = east(d, a, cosLat);
        double dy = north(d, a);
        DoubleUnaryOperator gap = f -> flat(f * bx, f * by, cx, cy, dx, dy);
        double lo = 0;
        double hi = 1;
        for (int n = 0; n < SEARCH_STEPS; n++) {
            double m1 = lo + (hi - lo) / 3;
            double m2 = hi - (hi - lo) / 3;
            if (gap.applyAsDouble(m1) < gap.applyAsDouble(m2)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double nearest = (lo + hi) / 2;
        if (gap.applyAsDouble(nearest) > LIMIT_M) {
            return null;
        }
        double entry = nearest;
        double before = 0;
        double exit = nearest;
        double after = 1;
        for (int n = 0; n < SEARCH_STEPS; n++) {
            double mid = (before + entry) / 2;
            if (gap.applyAsDouble(mid) <= LIMIT_M) {
                entry = mid;
            } else {
                before = mid;
            }
            mid = (exit + after) / 2;
            if (gap.applyAsDouble(mid) <= LIMIT_M) {
                exit = mid;
            } else {
                after = mid;
            }
        }
        return new double[] {entry, exit};
    }

    private static double east(TrackPoint q, TrackPoint origin, double cosLat) {
        return Math.toRadians(q.longitude() - origin.longitude()) * EARTH_RADIUS_M * cosLat;
    }

    private static double north(TrackPoint q, TrackPoint origin) {
        return Math.toRadians(q.latitude() - origin.latitude()) * EARTH_RADIUS_M;
    }
}
