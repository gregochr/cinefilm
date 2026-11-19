package gregochr.cinefilm.credits.learn;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rows a training run reads from a features run's {@code features.csv}
 * ({@code docs/engineering/credits-classifier-plan.md}, "The training scaffold (L4b-4a)"): the rows that train, and
 * the key of every row in the file, kept or dropped.
 *
 * <p><strong>Only development rows are read.</strong> The first row whose side is not {@code dev}, or whose fold is
 * empty, stops the run. Rows must be in the order {@link CreditsFeaturesJob} writes them: {@code (mediaFileId, kind,
 * offsetMs)}, each strictly after the one before. The left-out rows, the rows with no time features and, without
 * {@code withDense}, the dense rows are dropped; with {@code litTextVariant}, a {@code LIT_OPENING} or
 * {@code LIT_RUN} row that looks like lit text is credits before the drops.
 *
 * @param kept the rows that train, in file order
 * @param mediaFileIds every data row's media file id, in file order
 * @param kinds every data row's kind, {@link #COARSE_KIND} or {@link #DENSE_KIND}
 * @param offsetsMs every data row's offset in milliseconds
 */
record TrainRows(List<Kept> kept, long[] mediaFileIds, byte[] kinds, long[] offsetsMs) {

    /** The {@code kinds} value of a coarse row. */
    static final byte COARSE_KIND = 0;
    /** The {@code kinds} value of a dense row. */
    static final byte DENSE_KIND = 1;

    private static final String COARSE = FilmCheck.COARSE;
    private static final String DENSE = FilmCheck.DENSE;
    private static final int P10 = 1;
    private static final int P50 = 2;
    private static final int P90 = 3;
    private static final int BANDS = 11;
    private static final int LIT_TEXT_MIN_BANDS = 3;
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * A row that is kept for training.
     *
     * @param index its index among {@code features.csv}'s data rows, 0-based, blank lines not counted
     * @param fold its fold, 0 to 4
     * @param coarse whether it is a coarse frame
     * @param label 1 for credits, 0 for film
     * @param features its {@link CreditsFeatures#COUNT} features
     */
    record Kept(int index, int fold, boolean coarse, int label, double[] features) {
    }

    /**
     * Reads {@code features.csv}, one line at a time.
     *
     * @param features the file
     * @param withDense whether dense rows train too
     * @param litTextVariant whether lit-text rows are relabelled as credits
     * @return the kept rows and every row's key
     * @throws IOException when the file cannot be read
     * @throws IllegalArgumentException when the file is not what a features run writes, a row is a test row or has
     *     no fold, or rows are out of order
     */
    static TrainRows read(Path features, boolean withDense, boolean litTextVariant) throws IOException {
        List<Kept> kept = new ArrayList<>();
        long[] ids = new long[INITIAL_CAPACITY];
        byte[] kinds = new byte[INITIAL_CAPACITY];
        long[] offsets = new long[INITIAL_CAPACITY];
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(features)) {
            List<String> header = fields(reader.readLine());
            if (!FeatureRows.COLUMNS.equals(header)) {
                throw new IllegalArgumentException("features.csv does not start with the features header");
            }
            long[] previous = null;
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                List<String> row = fields(line);
                if (row.isEmpty()) {
                    continue;
                }
                String where = "features.csv line " + number + ": ";
                if (row.size() != FeatureRows.COLUMNS.size()) {
                    throw new IllegalArgumentException(where + "expected " + FeatureRows.COLUMNS.size()
                        + " fields, found " + row.size());
                }
                if (!row.get(2).equals(Side.DEV.json()) || row.get(1).isEmpty()) {
                    throw new IllegalArgumentException(where
                        + "a row that is not a development row with a fold is refused; nothing was written");
                }
                previous = checkOrder(row, previous, where);
                if (count == ids.length) {
                    ids = Arrays.copyOf(ids, count * 2);
                    kinds = Arrays.copyOf(kinds, count * 2);
                    offsets = Arrays.copyOf(offsets, count * 2);
                }
                ids[count] = previous[0];
                kinds[count] = (byte) previous[1];
                offsets[count] = previous[2];
                Kept train = train(count, row, withDense, litTextVariant, where);
                if (train != null) {
                    kept.add(train);
                }
                count++;
            }
        }
        return new TrainRows(kept, Arrays.copyOf(ids, count), Arrays.copyOf(kinds, count),
            Arrays.copyOf(offsets, count));
    }

    static List<String> fields(String line) {
        List<List<String>> parsed = line == null ? List.of() : CsvReader.parse(line);
        return parsed.isEmpty() ? List.of() : parsed.getFirst();
    }

    /** The row's {@code (mediaFileId, kind, offsetMs)}, which must be strictly after the previous row's. */
    static long[] checkOrder(List<String> row, long[] previous, String where) {
        String kind = row.get(3);
        if (!kind.equals(COARSE) && !kind.equals(DENSE)) {
            throw new IllegalArgumentException(where + "kind must be coarse or dense: " + kind);
        }
        long[] key = {number(row.get(0), "mediaFileId", where), kind.equals(COARSE) ? COARSE_KIND : DENSE_KIND,
            number(row.get(4), "offsetMs", where)};
        if (previous != null && compare(previous, key) >= 0) {
            throw new IllegalArgumentException(where
                + "rows are not in (mediaFileId, kind, offsetMs) order, each after the one before");
        }
        return key;
    }

    private static int compare(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Long.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    static long number(String text, String name, String where) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + name + " is not a whole number: " + text, e);
        }
    }

    /** The row to train on, or null when it is dropped. */
    private static Kept train(int index, List<String> row, boolean withDense, boolean litTextVariant,
                              String where) {
        long fold = number(row.get(1), "fold", where);
        if (fold < 0 || fold >= FoldModels.FOLDS) {
            throw new IllegalArgumentException(where + "fold must be 0 to " + (FoldModels.FOLDS - 1) + ": " + fold);
        }
        double[] features = features(row, where);
        int label = label(row.get(5), features, litTextVariant, where);
        boolean coarse = row.get(3).equals(COARSE);
        if (label < 0 || !CreditsFeatures.hasTimeFeatures(features) || !coarse && !withDense) {
            return null;
        }
        return new Kept(index, (int) fold, coarse, label, features);
    }

    static double[] features(List<String> row, String where) {
        double[] features = new double[CreditsFeatures.COUNT];
        for (int i = 0; i < features.length; i++) {
            String text = row.get(FeatureRows.COLUMNS.size() - features.length + i);
            if (text.isEmpty() && (i == CreditsFeatures.MAD || i == CreditsFeatures.INK_CHANGE)) {
                features[i] = Double.NaN;
                continue;
            }
            try {
                features[i] = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + CreditsFeatures.COLUMNS.get(i)
                    + " is not a number: " + text, e);
            }
            if (!Double.isFinite(features[i])) {
                throw new IllegalArgumentException(where + CreditsFeatures.COLUMNS.get(i) + " is not finite");
            }
        }
        return features;
    }

    /** 1 for credits, 0 for film, -1 for a left-out row. */
    private static int label(String text, double[] features, boolean litText, String where) {
        if (text.equals("credits")) {
            return 1;
        }
        if (text.equals("film")) {
            return 0;
        }
        LeftOutReason reason;
        try {
            reason = LeftOutReason.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + "unknown label: " + text, e);
        }
        boolean lit = reason == LeftOutReason.LIT_OPENING || reason == LeftOutReason.LIT_RUN;
        return litText && lit && isLitText(features) ? 1 : -1;
    }

    /** A frame {@link OpeningType#frameKind} calls {@code LIT} that has at least three text bands. */
    private static boolean isLitText(double[] f) {
        return OpeningType.frameKind(f[0], (int) f[P10], (int) f[P50], (int) f[P90]) == FrameKind.LIT
            && f[BANDS] >= LIT_TEXT_MIN_BANDS;
    }
}
