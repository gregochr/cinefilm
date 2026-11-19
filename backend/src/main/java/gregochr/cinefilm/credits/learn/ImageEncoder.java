package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import java.util.List;

/**
 * Turns pictures into embedding vectors. One implementation reads an ONNX file ({@link OnnxImageEncoder}); the
 * interface lets everything else be tested without the native library.
 */
interface ImageEncoder extends AutoCloseable {

    /**
     * Encodes a batch.
     *
     * @param frames the pictures, each {@link EncoderSpec#size()} square
     * @return one row of {@link EncoderSpec#dimension()} floats per frame, in the frames' order
     * @throws IllegalArgumentException when a frame is not the right size
     * @throws IllegalStateException when the encoder's output is not usable
     */
    float[][] encode(List<RgbFrame> frames);

    /** Releases what the encoder holds; it throws nothing. */
    @Override
    void close();
}
