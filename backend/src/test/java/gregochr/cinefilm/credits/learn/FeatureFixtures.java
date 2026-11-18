package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;

/** Hand-made crops and expected feature arrays for the {@link CreditsFeatures} tests. */
final class FeatureFixtures {

    private FeatureFixtures() {
    }

    /** A crop of one value. */
    static GreyFrame uniform(int width, int height, int value) {
        return new Canvas(width, height, value).build();
    }

    /** The negative of a crop: {@code 255 - v} for every pixel. */
    static GreyFrame negative(GreyFrame frame) {
        byte[] out = new byte[frame.width() * frame.height()];
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                out[y * frame.width() + x] = (byte) (255 - frame.luma(x, y));
            }
        }
        return new GreyFrame(frame.width(), frame.height(), out);
    }

    /**
     * A crop drawn in text, one string per row: {@code .} is 128, {@code #} 255, {@code o} 0, {@code h} 192, {@code l}
     * 64, {@code p} 140, {@code q} 115. Every row must be as long as the first.
     */
    static GreyFrame rows(String... rows) {
        Canvas canvas = new Canvas(rows[0].length(), rows.length, 0);
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                canvas.pixel(x, y, legend(rows[y].charAt(x)));
            }
        }
        return canvas.build();
    }

    private static int legend(char c) {
        return switch (c) {
            case '.' -> 128;
            case '#' -> 255;
            case 'o' -> 0;
            case 'h' -> 192;
            case 'l' -> 64;
            case 'p' -> 140;
            case 'q' -> 115;
            default -> throw new IllegalArgumentException("No legend for " + c);
        };
    }

    /** A feature array of zeros except the named ones; the time features are 0 too. */
    static double[] expected(Object... namesAndValues) {
        double[] out = new double[CreditsFeatures.COUNT];
        for (int i = 0; i < namesAndValues.length; i += 2) {
            int index = CreditsFeatures.COLUMNS.indexOf((String) namesAndValues[i]);
            if (index < 0) {
                throw new IllegalArgumentException("No feature " + namesAndValues[i]);
            }
            out[index] = ((Number) namesAndValues[i + 1]).doubleValue();
        }
        return out;
    }

    /** One feature by name. */
    static double of(double[] features, String name) {
        return features[CreditsFeatures.COLUMNS.indexOf(name)];
    }

    /** A drawing surface: a background and rectangles of one value. */
    static final class Canvas {
        private final int width;
        private final int height;
        private final int[] pixels;

        Canvas(int width, int height, int background) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
            java.util.Arrays.fill(pixels, background);
        }

        /** Sets one pixel. */
        Canvas pixel(int x, int y, int value) {
            pixels[y * width + x] = value;
            return this;
        }

        /** Fills columns {@code [x, x + w)} and rows {@code [y, y + h)}. */
        Canvas rect(int x, int y, int w, int h, int value) {
            for (int j = y; j < y + h; j++) {
                for (int i = x; i < x + w; i++) {
                    pixel(i, j, value);
                }
            }
            return this;
        }

        /**
         * A line of text: {@code strokes} strokes, each 2 px wide, 2 px apart, from column {@code x}, over rows
         * {@code [y, y + rows)}.
         */
        Canvas text(int x, int y, int rows, int strokes, int value) {
            for (int s = 0; s < strokes; s++) {
                rect(x + 4 * s, y, 2, rows, value);
            }
            return this;
        }

        /** The crop. */
        GreyFrame build() {
            byte[] out = new byte[pixels.length];
            for (int i = 0; i < pixels.length; i++) {
                out[i] = (byte) pixels[i];
            }
            return new GreyFrame(width, height, out);
        }
    }
}
