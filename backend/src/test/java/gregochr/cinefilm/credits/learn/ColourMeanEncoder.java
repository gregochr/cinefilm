package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import java.util.ArrayList;
import java.util.List;

/**
 * A pure-Java {@link ImageEncoder} for tests: each frame's mean red, green and blue (0 to 255), so the embedding
 * says which stored frame it came from. It records the size of each batch.
 */
final class ColourMeanEncoder implements ImageEncoder {

    /** The size of each batch it was given, in order. */
    final List<Integer> batches = new ArrayList<>();
    boolean closed;
    private final Runnable beforeEncode;

    ColourMeanEncoder() {
        this(() -> { });
    }

    /** @param beforeEncode runs at the start of every {@link #encode}, and may throw */
    ColourMeanEncoder(Runnable beforeEncode) {
        this.beforeEncode = beforeEncode;
    }

    @Override
    public float[][] encode(List<RgbFrame> frames) {
        beforeEncode.run();
        batches.add(frames.size());
        float[][] rows = new float[frames.size()][3];
        for (int i = 0; i < frames.size(); i++) {
            RgbFrame frame = frames.get(i);
            double[] sums = new double[3];
            for (int y = 0; y < frame.height(); y++) {
                for (int x = 0; x < frame.width(); x++) {
                    int rgb = frame.rgb(x, y);
                    sums[0] += rgb >> 16 & 0xFF;
                    sums[1] += rgb >> 8 & 0xFF;
                    sums[2] += rgb & 0xFF;
                }
            }
            for (int c = 0; c < 3; c++) {
                rows[i][c] = (float) (sums[c] / (frame.width() * frame.height()));
            }
        }
        return rows;
    }

    @Override
    public void close() {
        closed = true;
    }
}
