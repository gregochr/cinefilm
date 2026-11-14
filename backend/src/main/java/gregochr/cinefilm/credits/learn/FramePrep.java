package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.ActiveArea;
import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.RgbFrame;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Turns the 480 pixel colour frames ffmpeg writes into the pictures the credits classifier learns from and sees:
 * a 240 pixel grey frame and a 224 by 224 colour JPEG. The dataset job and the product both use these exact methods,
 * so training and the product see identical pixels. Pure and static; every rule is integer or fixed-formula
 * arithmetic, so the result never depends on the machine.
 *
 * <p><strong>The luma here is full range</strong> (black 0, white 255, BT.601 weights), because ffmpeg's {@code rgb24}
 * output is full range. The product's existing {@code format=gray} frames, which the 2b rules and
 * {@link ActiveArea#of} read, are <em>limited</em> range (black 16). A threshold written for one is wrong for the
 * other: any threshold applied to {@code FramePrep} frames is re-derived on development frames in L3a before use.
 */
public final class FramePrep {

    /** The side of the square colour picture the model sees. */
    public static final int COLOUR_SIZE = 224;

    private static final float JPEG_QUALITY = 0.95f;

    private FramePrep() {
    }

    // ---- Grey ----------------------------------------------------------------------------------------------------

    /**
     * The full-range BT.601 luma of each pixel, {@code (77 R + 150 G + 29 B) >> 8}; the weights sum to 256, so white is
     * 255 and black 0.
     *
     * @param frame the colour frame
     * @return a grey frame of the same size
     */
    public static GreyFrame luma(RgbFrame frame) {
        int width = frame.width();
        byte[] out = new byte[width * frame.height()];
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.rgb(x, y);
                int red = rgb >> 16 & 0xFF;
                int green = rgb >> 8 & 0xFF;
                int blue = rgb & 0xFF;
                out[y * width + x] = (byte) ((77 * red + 150 * green + 29 * blue) >> 8);
            }
        }
        return new GreyFrame(width, frame.height(), out);
    }

    /**
     * Halves a frame: each output pixel is {@code (a + b + c + d + 2) >> 2} over the 2 by 2 block at {@code (2x, 2y)}.
     * An odd last row or column is dropped.
     *
     * @param frame the frame, at least 2 by 2
     * @return a frame of {@code floor(w / 2)} by {@code floor(h / 2)}
     * @throws IllegalArgumentException when the frame is narrower or shorter than 2
     */
    public static GreyFrame boxHalf(GreyFrame frame) {
        if (frame.width() < 2 || frame.height() < 2) {
            throw new IllegalArgumentException(
                "A frame must be at least 2x2 to halve, was " + frame.width() + "x" + frame.height());
        }
        int width = frame.width() / 2;
        int height = frame.height() / 2;
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sum = frame.luma(2 * x, 2 * y) + frame.luma(2 * x + 1, 2 * y)
                    + frame.luma(2 * x, 2 * y + 1) + frame.luma(2 * x + 1, 2 * y + 1);
                out[y * width + x] = (byte) ((sum + 2) >> 2);
            }
        }
        return new GreyFrame(width, height, out);
    }

    /**
     * The grey frame the model sees: {@link #boxHalf} of {@link #luma}. A 480 pixel frame gives 240 pixels.
     *
     * @param frame the colour frame
     * @return the grey frame
     */
    public static GreyFrame grey(RgbFrame frame) {
        return boxHalf(luma(frame));
    }

    // ---- Colour --------------------------------------------------------------------------------------------------

    /**
     * Cuts the picture area out of a frame.
     *
     * @param frame the frame, at the 480 pixel size the area was measured on
     * @param area the picture area in the frame's own coordinates
     * @return the cut-out
     * @throws IllegalArgumentException when the area does not lie fully inside the frame
     */
    public static RgbFrame crop(RgbFrame frame, ActiveArea area) {
        if (area.left() < 0 || area.top() < 0 || area.width() < 1 || area.height() < 1
            || area.left() + area.width() > frame.width() || area.top() + area.height() > frame.height()) {
            throw new IllegalArgumentException("The area " + area + " does not lie inside the " + frame.width() + "x"
                + frame.height() + " frame");
        }
        byte[] out = new byte[area.width() * area.height() * 3];
        int at = 0;
        for (int y = 0; y < area.height(); y++) {
            for (int x = 0; x < area.width(); x++) {
                int rgb = frame.rgb(area.left() + x, area.top() + y);
                out[at++] = (byte) (rgb >> 16);
                out[at++] = (byte) (rgb >> 8);
                out[at++] = (byte) rgb;
            }
        }
        return new RgbFrame(area.width(), area.height(), out);
    }

    /**
     * Resamples to a size, bilinearly and per channel, stretching rather than keeping the aspect ratio. The source
     * position of an output pixel is {@code (x + 0.5) * srcW / outW - 0.5} clamped to the frame; the result is rounded
     * half up.
     *
     * @param frame the source
     * @param outWidth the width to make
     * @param outHeight the height to make
     * @return the resampled frame
     * @throws IllegalArgumentException when the size is out of range
     */
    public static RgbFrame squash(RgbFrame frame, int outWidth, int outHeight) {
        if (outWidth < 1 || outWidth > RgbFrame.MAX_DIMENSION || outHeight < 1
            || outHeight > RgbFrame.MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "Frame size must be 1 to " + RgbFrame.MAX_DIMENSION + " in each direction, was " + outWidth + "x"
                    + outHeight);
        }
        int srcW = frame.width();
        int srcH = frame.height();
        byte[] out = new byte[outWidth * outHeight * 3];
        int at = 0;
        for (int y = 0; y < outHeight; y++) {
            double sy = clamp((y + 0.5) * srcH / outHeight - 0.5, srcH - 1);
            int y0 = (int) Math.floor(sy);
            int y1 = Math.min(y0 + 1, srcH - 1);
            double fy = sy - y0;
            for (int x = 0; x < outWidth; x++) {
                double sx = clamp((x + 0.5) * srcW / outWidth - 0.5, srcW - 1);
                int x0 = (int) Math.floor(sx);
                int x1 = Math.min(x0 + 1, srcW - 1);
                double fx = sx - x0;
                int p00 = frame.rgb(x0, y0);
                int p10 = frame.rgb(x1, y0);
                int p01 = frame.rgb(x0, y1);
                int p11 = frame.rgb(x1, y1);
                for (int shift = 16; shift >= 0; shift -= 8) {
                    double v = (1 - fy) * ((1 - fx) * (p00 >> shift & 0xFF) + fx * (p10 >> shift & 0xFF))
                        + fy * ((1 - fx) * (p01 >> shift & 0xFF) + fx * (p11 >> shift & 0xFF));
                    out[at++] = (byte) Math.min(255, Math.max(0, (int) Math.floor(v + 0.5)));
                }
            }
        }
        return new RgbFrame(outWidth, outHeight, out);
    }

    private static double clamp(double value, int max) {
        return Math.min((double) max, Math.max(0.0, value));
    }

    /**
     * The colour picture as it is stored: the area cropped, squashed to {@value #COLOUR_SIZE} square and encoded.
     *
     * @param frame the 480 pixel colour frame
     * @param area the picture area in the frame's own coordinates
     * @return a JPEG
     */
    public static byte[] colourJpeg(RgbFrame frame, ActiveArea area) {
        return jpeg(squash(crop(frame, area), COLOUR_SIZE, COLOUR_SIZE));
    }

    /**
     * The colour picture as the model sees it: the stored JPEG decoded again. The product does this in memory, and
     * training decodes what it stored, so both see identical pixels.
     *
     * @param frame the 480 pixel colour frame
     * @param area the picture area in the frame's own coordinates
     * @return a {@value #COLOUR_SIZE} square frame
     */
    public static RgbFrame colourForModel(RgbFrame frame, ActiveArea area) {
        return decodeJpeg(colourJpeg(frame, area));
    }

    /**
     * Encodes a frame as a JPEG at quality 0.95 with no metadata or thumbnail. The same frame always gives the same
     * bytes.
     *
     * @param frame the frame
     * @return the JPEG bytes
     * @throws UncheckedIOException when the encoder fails
     */
    public static byte[] jpeg(RgbFrame frame) {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                image.setRGB(x, y, frame.rgb(x, y));
            }
        }
        return write("JPEG", "jpeg", image);
    }

    /**
     * Writes an image with the named format's {@code ImageWriter} into a {@link MemoryCacheImageOutputStream}, so
     * nothing touches the disk cache. The JPEG writer is set to quality {@value #JPEG_QUALITY}; the PNG writer has no
     * quality.
     */
    private static byte[] write(String what, String format, BufferedImage image) {
        return encode(what, bytes -> {
            ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
            try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                ImageWriteParam param = writer.getDefaultWriteParam();
                if ("jpeg".equals(format)) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(JPEG_QUALITY);
                }
                writer.setOutput(out);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
        });
    }

    /** Something that writes a picture's bytes. */
    @FunctionalInterface
    interface PictureWriter {
        void write(OutputStream out) throws IOException;
    }

    /** Runs a writer into memory; the encoders cannot fail on memory, so a failure is a defect, not an input. */
    static byte[] encode(String what, PictureWriter writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            writer.write(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("The " + what + " could not be written", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes a JPEG.
     *
     * @param data the JPEG bytes
     * @return the frame
     * @throws IllegalArgumentException when the bytes are not a readable picture
     */
    public static RgbFrame decodeJpeg(byte[] data) {
        BufferedImage image = decode(data, "JPEG");
        byte[] out = new byte[image.getWidth() * image.getHeight() * 3];
        int at = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                out[at++] = (byte) (rgb >> 16);
                out[at++] = (byte) (rgb >> 8);
                out[at++] = (byte) rgb;
            }
        }
        return new RgbFrame(image.getWidth(), image.getHeight(), out);
    }

    // ---- PNG -----------------------------------------------------------------------------------------------------

    /**
     * Encodes a grey frame as a lossless 8-bit greyscale PNG.
     *
     * @param frame the frame
     * @return the PNG bytes
     * @throws UncheckedIOException when the encoder fails
     */
    public static byte[] png(GreyFrame frame) {
        byte[] pixels = new byte[frame.width() * frame.height()];
        for (int y = 0; y < frame.height(); y++) {
            for (int x = 0; x < frame.width(); x++) {
                pixels[y * frame.width() + x] = (byte) frame.luma(x, y);
            }
        }
        BufferedImage image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_BYTE_GRAY);
        image.getRaster().setDataElements(0, 0, frame.width(), frame.height(), pixels);
        return write("PNG", "png", image);
    }

    /**
     * Decodes a greyscale PNG written by {@link #png}.
     *
     * @param data the PNG bytes
     * @return the frame, exactly as it was encoded
     * @throws IllegalArgumentException when the bytes are not an 8-bit greyscale picture
     */
    public static GreyFrame decodePng(byte[] data) {
        BufferedImage image = decode(data, "PNG");
        if (image.getType() != BufferedImage.TYPE_BYTE_GRAY) {
            throw new IllegalArgumentException("The PNG is not an 8-bit greyscale picture");
        }
        byte[] pixels = (byte[]) image.getRaster().getDataElements(0, 0, image.getWidth(), image.getHeight(), null);
        return new GreyFrame(image.getWidth(), image.getHeight(), pixels);
    }

    private static BufferedImage decode(byte[] data, String what) {
        try {
            BufferedImage image = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(data)));
            if (image == null) {
                throw new IllegalArgumentException("The bytes are not a readable " + what + " picture");
            }
            return image;
        } catch (IOException e) {
            throw new IllegalArgumentException("The " + what + " picture could not be read: " + e.getMessage(), e);
        }
    }
}
