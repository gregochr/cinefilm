package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.util.ArrayList;
import java.util.List;

/**
 * The 33 features of one cropped grey frame (plan, "L3a specification", "{@code CreditsFeatures}"). Pure.
 *
 * <p>The frame is the crop from {@link ActiveCrop}. The features form one {@code double[]} in the order of
 * {@link #COLUMNS}; integer-valued ones such as {@code p10} are stored as doubles. Two methods give the array:
 * <ul>
 *   <li>{@link #features(GreyFrame, GreyFrame, GreyFrame)} takes the frame's two neighbour crops and fills all 33;
 *   <li>{@link #features(GreyFrame)} is for a frame with no neighbours (a dense frame without both): the 31 per-frame
 *       features are filled and the two time features, {@code mad} and {@code inkChange}, are {@link Double#NaN}. NaN
 *       is the one "no time features" state; {@link #hasTimeFeatures(double[])} tests for it, and the CSV writer
 *       leaves those two cells empty.
 * </ul>
 * Choosing the neighbours (by index in {@code complete.json}) is the caller's job, not this class's.
 */
public final class CreditsFeatures {

    /** The version of the feature definitions; bump it when any feature's meaning changes. */
    public static final int FEATURE_VERSION = 1;

    /** The number of features. */
    public static final int COUNT = 33;

    /** The feature names, in order; the CSV column names. */
    public static final List<String> COLUMNS = columns();

    /** The index of {@code mad} in the feature array. */
    static final int MAD = 31;
    /** The index of {@code inkChange} in the feature array. */
    static final int INK_CHANGE = 32;

    /** A pixel is ink when it is at least this far from the median (plan, "CreditsFeatures"). */
    static final int INK_DISTANCE = 64;
    /** A pixel is plain when it is at most this far from the median. */
    static final int PLAIN_DISTANCE = 12;
    /** An edge pair differs by at least this. */
    static final int EDGE_DELTA = 48;
    /** An ink run is thin when it is at most this long. */
    static final int THIN_RUN_MAX = 6;
    /** A text band is at least this many rows tall. */
    static final int BAND_MIN_ROWS = 3;
    /** A text band is at most this many rows tall. */
    static final int BAND_MAX_ROWS = 20;
    /** The fewest bands for which {@code lineSpacingCv} is not 0. */
    static final int CV_MIN_BANDS = 3;

    private static final int LEVELS = 256;
    private static final int GRID = 4;
    private static final int P10 = 10;
    private static final int P50 = 50;
    private static final int P90 = 90;
    private static final double THIN_PER_K = 1000.0;
    /** Text-row lower bound: {@code 50 * e >= w - 1}, a share of at least 0.02. */
    private static final int TEXT_ROW_LOW_FACTOR = 50;
    /** Text-row upper bound: {@code 5 * e <= 3 * (w - 1)}, a share of at most 0.6. */
    private static final int TEXT_ROW_HIGH_LEFT = 5;
    private static final int TEXT_ROW_HIGH_RIGHT = 3;
    private static final int BOTTOM_FRACTION = 5;

    private CreditsFeatures() {
    }

    private static List<String> columns() {
        List<String> names = new ArrayList<>(List.of("mean", "p10", "p50", "p90", "plain", "ink", "hEdge", "vEdge",
            "thinPerK", "thinShare", "textRows", "bands", "lineSpacingCv", "centralInk", "bottomInk"));
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                names.add("grid" + i + j);
            }
        }
        names.add("mad");
        names.add("inkChange");
        return List.copyOf(names);
    }

    /**
     * The features of a frame that has no neighbours: the 31 per-frame ones, and NaN for {@code mad} and
     * {@code inkChange}.
     *
     * @param crop the frame inside the active crop
     * @return 33 values in {@link #COLUMNS} order, the last two NaN
     */
    public static double[] features(GreyFrame crop) {
        double[] out = perFrame(Analysis.of(crop));
        out[MAD] = Double.NaN;
        out[INK_CHANGE] = Double.NaN;
        return out;
    }

    /**
     * All 33 features of a frame and its two neighbours.
     *
     * @param crop the frame inside the active crop
     * @param previous one neighbour's crop, the same size as {@code crop}
     * @param next the other neighbour's crop, the same size as {@code crop}
     * @return 33 values in {@link #COLUMNS} order
     * @throws IllegalArgumentException when a neighbour is not the size of {@code crop}
     */
    public static double[] features(GreyFrame crop, GreyFrame previous, GreyFrame next) {
        requireSameSize(crop, previous);
        requireSameSize(crop, next);
        Analysis a = Analysis.of(crop);
        Analysis p = Analysis.of(previous);
        Analysis n = Analysis.of(next);
        double[] out = perFrame(a);
        long count = (long) crop.width() * crop.height();
        long s1 = 0;
        long s2 = 0;
        long c1 = 0;
        long c2 = 0;
        for (int i = 0; i < a.luma.length; i++) {
            s1 += Math.abs(a.luma[i] - p.luma[i]);
            s2 += Math.abs(a.luma[i] - n.luma[i]);
            if (a.ink[i] != p.ink[i]) {
                c1++;
            }
            if (a.ink[i] != n.ink[i]) {
                c2++;
            }
        }
        out[MAD] = (double) (s1 + s2) / (2L * count);
        out[INK_CHANGE] = (double) (c1 + c2) / (2L * count);
        return out;
    }

    /**
     * Whether a feature array has its time features.
     *
     * @param features an array from this class
     * @return false when {@code mad} and {@code inkChange} are NaN
     */
    public static boolean hasTimeFeatures(double[] features) {
        return !Double.isNaN(features[MAD]) && !Double.isNaN(features[INK_CHANGE]);
    }

    private static void requireSameSize(GreyFrame crop, GreyFrame other) {
        if (crop.width() != other.width() || crop.height() != other.height()) {
            throw new IllegalArgumentException("A neighbour is " + other.width() + "x" + other.height()
                + ", the frame is " + crop.width() + "x" + crop.height());
        }
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double[] perFrame(Analysis a) {
        int w = a.width;
        int h = a.height;
        long n = (long) w * h;
        double[] out = new double[COUNT];
        int k = 0;
        out[k++] = (double) a.sum / n;
        out[k++] = a.percentile(P10);
        out[k++] = a.median;
        out[k++] = a.percentile(P90);
        out[k++] = ratio(a.plain, n);
        out[k++] = ratio(a.inkCount, n);

        long horizontalEdges = 0;
        long verticalEdges = 0;
        long thin = 0;
        long runs = 0;
        int[] rowEdges = new int[h];
        for (int y = 0; y < h; y++) {
            int run = 0;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (x + 1 < w && Math.abs(a.luma[i + 1] - a.luma[i]) >= EDGE_DELTA) {
                    rowEdges[y]++;
                }
                if (y + 1 < h && Math.abs(a.luma[i + w] - a.luma[i]) >= EDGE_DELTA) {
                    verticalEdges++;
                }
                if (a.ink[i]) {
                    run++;
                } else if (run > 0) {
                    runs++;
                    thin += run <= THIN_RUN_MAX ? 1 : 0;
                    run = 0;
                }
            }
            if (run > 0) {
                runs++;
                thin += run <= THIN_RUN_MAX ? 1 : 0;
            }
            horizontalEdges += rowEdges[y];
        }
        out[k++] = ratio(horizontalEdges, (long) (w - 1) * h);
        out[k++] = ratio(verticalEdges, (long) w * (h - 1));
        out[k++] = THIN_PER_K * thin / n;
        out[k++] = ratio(thin, runs);

        boolean[] textRow = new boolean[h];
        long textRows = 0;
        for (int y = 0; y < h; y++) {
            int e = rowEdges[y];
            textRow[y] = TEXT_ROW_LOW_FACTOR * e >= w - 1 && TEXT_ROW_HIGH_LEFT * e <= TEXT_ROW_HIGH_RIGHT * (w - 1);
            if (textRow[y]) {
                textRows++;
            }
        }
        out[k++] = ratio(textRows, h);
        List<Double> centres = bandCentres(textRow);
        out[k++] = centres.size();
        out[k++] = lineSpacingCv(centres);

        long central = 0;
        long bottom = 0;
        long[] grid = new long[GRID * GRID];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!a.ink[y * w + x]) {
                    continue;
                }
                if (x >= w / 4 && x < 3 * w / 4) {
                    central++;
                }
                if (y >= h - h / BOTTOM_FRACTION) {
                    bottom++;
                }
                grid[gridCell(y, h) * GRID + gridCell(x, w)]++;
            }
        }
        out[k++] = ratio(central, a.inkCount);
        out[k++] = ratio(bottom, a.inkCount);
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                long cellPixels = (long) ((j + 1) * w / GRID - j * w / GRID) * ((i + 1) * h / GRID - i * h / GRID);
                out[k++] = ratio(grid[i * GRID + j], cellPixels);
            }
        }
        return out;
    }

    /** The grid cell (0 to 3) holding coordinate {@code c} on a side of {@code size}: cell {@code j} is
     * {@code [j * size / 4, (j + 1) * size / 4)}. */
    private static int gridCell(int c, int size) {
        int cell = 0;
        while (cell < GRID - 1 && c >= (cell + 1) * size / GRID) {
            cell++;
        }
        return cell;
    }

    private static List<Double> bandCentres(boolean[] textRow) {
        List<Double> centres = new ArrayList<>();
        int y = 0;
        while (y < textRow.length) {
            if (!textRow[y]) {
                y++;
                continue;
            }
            int first = y;
            while (y < textRow.length && textRow[y]) {
                y++;
            }
            int last = y - 1;
            int rows = last - first + 1;
            if (rows >= BAND_MIN_ROWS && rows <= BAND_MAX_ROWS) {
                centres.add((first + last) / 2.0);
            }
        }
        return centres;
    }

    private static double lineSpacingCv(List<Double> centres) {
        if (centres.size() < CV_MIN_BANDS) {
            return 0;
        }
        int gaps = centres.size() - 1;
        double sum = 0;
        for (int i = 0; i < gaps; i++) {
            sum += centres.get(i + 1) - centres.get(i);
        }
        double m = sum / gaps;
        double squares = 0;
        for (int i = 0; i < gaps; i++) {
            double g = centres.get(i + 1) - centres.get(i);
            squares += (g - m) * (g - m);
        }
        return Math.sqrt(squares / gaps) / m;
    }

    /** One crop's pixels, histogram and ink mask. */
    private static final class Analysis {
        private final int width;
        private final int height;
        private final int[] luma;
        private final boolean[] ink;
        private final long[] histogram = new long[LEVELS];
        private final long count;
        private long sum;
        private int median;
        private long plain;
        private long inkCount;

        private Analysis(GreyFrame crop) {
            width = crop.width();
            height = crop.height();
            count = (long) width * height;
            luma = new int[width * height];
            ink = new boolean[width * height];
        }

        static Analysis of(GreyFrame crop) {
            Analysis a = new Analysis(crop);
            for (int y = 0; y < a.height; y++) {
                for (int x = 0; x < a.width; x++) {
                    int v = crop.luma(x, y);
                    a.luma[y * a.width + x] = v;
                    a.histogram[v]++;
                    a.sum += v;
                }
            }
            a.median = a.percentile(P50);
            for (int i = 0; i < a.luma.length; i++) {
                int distance = Math.abs(a.luma[i] - a.median);
                if (distance >= INK_DISTANCE) {
                    a.ink[i] = true;
                    a.inkCount++;
                }
                if (distance <= PLAIN_DISTANCE) {
                    a.plain++;
                }
            }
            return a;
        }

        int percentile(int percent) {
            return FrameStats.percentile(histogram, count, percent);
        }
    }
}
