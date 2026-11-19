package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.RgbFrame;
import java.util.List;

/** Builds the input tensor of an image encoder from pictures. Pure. */
final class EncoderInput {

    private EncoderInput() {
    }

    /**
     * The batch as one flat NCHW tensor: frames in list order, channels red, green, blue, each frame row by row.
     * Each value is {@code (v / 255 - mean[c]) / std[c]}, computed in double and then narrowed to float.
     *
     * @param frames the pictures
     * @param spec the encoder's size, mean and std
     * @return {@code frames.size() * 3 * size * size} floats
     * @throws IllegalArgumentException when a frame is not {@code size} by {@code size}
     */
    static float[] tensor(List<RgbFrame> frames, EncoderSpec spec) {
        int size = spec.size();
        int plane = size * size;
        float[] tensor = new float[frames.size() * 3 * plane];
        int at = 0;
        for (RgbFrame frame : frames) {
            if (frame.width() != size || frame.height() != size) {
                throw new IllegalArgumentException("frame is " + frame.width() + "x" + frame.height()
                    + ", the encoder takes " + size + "x" + size);
            }
            for (int c = 0; c < 3; c++) {
                double mean = spec.mean().get(c);
                double std = spec.std().get(c);
                int shift = 16 - 8 * c;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int v = frame.rgb(x, y) >> shift & 0xFF;
                        tensor[at++] = (float) ((v / 255.0 - mean) / std);
                    }
                }
            }
        }
        return tensor;
    }
}
