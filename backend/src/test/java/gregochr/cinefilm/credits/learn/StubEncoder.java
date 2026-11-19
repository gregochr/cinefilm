package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import java.util.ArrayList;
import java.util.List;

/** A pure-Java {@link ImageEncoder} for tests: it records what it was given and answers zeros. */
final class StubEncoder implements ImageEncoder {

    /** The size of each batch it was given, in order. */
    final List<Integer> batches = new ArrayList<>();
    /** Every frame it was given, in order. */
    final List<RgbFrame> frames = new ArrayList<>();
    boolean closed;
    private final int dimension;
    private final RuntimeException failure;

    StubEncoder(int dimension) {
        this(dimension, null);
    }

    /**
     * @param dimension the length of each answered row
     * @param failure thrown by every {@link #encode} when not null
     */
    StubEncoder(int dimension, RuntimeException failure) {
        this.dimension = dimension;
        this.failure = failure;
    }

    @Override
    public float[][] encode(List<RgbFrame> batch) {
        if (failure != null) {
            throw failure;
        }
        batches.add(batch.size());
        frames.addAll(batch);
        return new float[batch.size()][dimension];
    }

    @Override
    public void close() {
        closed = true;
    }
}
