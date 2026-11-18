package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-frame row work of the credits features tool ({@code docs/engineering/credits-classifier-plan.md},
 * "{@code CreditsFeatures}", time features, and "{@code CreditsFeaturesMain}", {@code features.csv}). Pure: frames come
 * from a {@link Loader}, rows go to a {@link DenseSink} or are returned, and no film directory is known here.
 *
 * <p>A coarse frame's neighbours are the coarse frames one index either side, the one neighbour twice at an end. A
 * dense frame's are the frames {@value #RADIUS} indices either side, counted in the film's own dense list with no
 * clamping, so the first and last {@value #RADIUS} have no time features.
 */
final class FeatureRows {

    /** The index distance of a dense frame's neighbours. */
    static final int RADIUS = 10;
    /** The columns of {@code features.csv}: the film and frame, then the 33 features. */
    static final List<String> COLUMNS = columns();

    private FeatureRows() {
    }

    private static List<String> columns() {
        List<String> names = new ArrayList<>(List.of("mediaFileId", "fold", "side", "kind", "offsetMs", "label"));
        names.addAll(CreditsFeatures.COLUMNS);
        return List.copyOf(names);
    }

    /** Gives the cropped frame at one index of a list. */
    @FunctionalInterface
    interface Loader {
        /**
         * Loads a frame.
         *
         * @param index the index in the list
         * @return the frame inside the active crop
         * @throws IOException when it cannot be read
         */
        GreyFrame load(int index) throws IOException;
    }

    /** Receives each dense frame, once, in list order. */
    @FunctionalInterface
    interface DenseSink {
        /**
         * Takes a frame.
         *
         * @param index the frame's index in the list
         * @param crop the frame
         * @param previous the frame {@value #RADIUS} indices before; null when the frame has no time features
         * @param next the frame {@value #RADIUS} indices after; null when the frame has no time features
         * @throws IOException when the sink cannot write
         */
        void accept(int index, GreyFrame crop, GreyFrame previous, GreyFrame next) throws IOException;
    }

    /**
     * The coarse frames' kinds and features.
     *
     * @param kinds each frame's kind, in list order
     * @param features each frame's 33 features, in list order
     */
    record Coarse(List<KindAt> kinds, List<double[]> features) {
    }

    /**
     * The 33 features of a frame.
     *
     * @param crop the frame
     * @param previous one neighbour, or null for none
     * @param next the other neighbour, or null for none
     * @return the features; the two time features are NaN when {@code previous} is null
     */
    static double[] features(GreyFrame crop, GreyFrame previous, GreyFrame next) {
        return previous == null ? CreditsFeatures.features(crop) : CreditsFeatures.features(crop, previous, next);
    }

    /**
     * Computes every coarse frame's kind and features, holding three frames at a time.
     *
     * @param offsets the coarse offsets, in list order
     * @param loader gives the frame at an index
     * @return the kinds and features
     * @throws IOException when a frame cannot be loaded
     */
    static Coarse coarse(List<Long> offsets, Loader loader) throws IOException {
        int n = offsets.size();
        List<KindAt> kinds = new ArrayList<>();
        List<double[]> features = new ArrayList<>();
        GreyFrame previous = null;
        GreyFrame current = n > 0 ? loader.load(0) : null;
        GreyFrame next = n > 1 ? loader.load(1) : null;
        for (int i = 0; i < n; i++) {
            features.add(n == 1 ? CreditsFeatures.features(current)
                : CreditsFeatures.features(current, i > 0 ? previous : next, i < n - 1 ? next : previous));
            kinds.add(new KindAt(offsets.get(i), OpeningType.frameKind(current)));
            previous = current;
            current = next;
            next = i + 2 < n ? loader.load(i + 2) : null;
        }
        return new Coarse(kinds, features);
    }

    /**
     * Streams {@code n} dense frames in list order through a buffer of {@code 2 * RADIUS + 1}. Frame {@code i} goes to
     * the sink when frame {@code i + RADIUS} has been read, or at once when {@code i < RADIUS}, or at the end when
     * fewer than {@code RADIUS} frames follow it.
     *
     * @param n how many dense frames the list holds
     * @param loader gives the frame at an index
     * @param sink receives each frame with its neighbours, or with none
     * @throws IOException when a frame cannot be loaded or the sink cannot write
     */
    static void dense(int n, Loader loader, DenseSink sink) throws IOException {
        List<GreyFrame> buffer = new ArrayList<>();
        int base = 0;
        int written = 0;
        for (int j = 0; j < n; j++) {
            buffer.add(loader.load(j));
            if (j < RADIUS) {
                sink.accept(j, buffer.getLast(), null, null);
                written = j + 1;
            } else if (j >= 2 * RADIUS) {
                int i = j - RADIUS;
                sink.accept(i, buffer.get(i - base), buffer.get(i - RADIUS - base), buffer.get(j - base));
                written = i + 1;
                buffer.removeFirst();
                base++;
            }
        }
        for (int i = written; i < n; i++) {
            sink.accept(i, buffer.get(i - base), null, null);
        }
    }

    /**
     * One row of {@code features.csv}, with its line feed.
     *
     * @param mediaFileId the film
     * @param fold its fold, or null
     * @param side its side
     * @param kind {@code coarse} or {@code dense}
     * @param offsetMs the frame's offset
     * @param label the frame's label
     * @param features its 33 features; NaN is written as an empty field, any other value with {@code Double.toString}
     * @return the row
     */
    static String row(long mediaFileId, Integer fold, Side side, String kind, long offsetMs, String label,
                      double[] features) {
        List<String> fields = new ArrayList<>(COLUMNS.size());
        fields.add(Long.toString(mediaFileId));
        fields.add(fold == null ? null : fold.toString());
        fields.add(side.json());
        fields.add(kind);
        fields.add(Long.toString(offsetMs));
        fields.add(label);
        for (double value : features) {
            fields.add(Double.isNaN(value) ? null : Double.toString(value));
        }
        return CsvWriter.row(fields);
    }

    /**
     * The header row of {@code features.csv}.
     *
     * @return the row
     */
    static String header() {
        return CsvWriter.row(COLUMNS);
    }
}
