package gregochr.cinefilm.plex;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Scales a stored artwork image down with the JDK's own {@code javax.imageio} (no dependency). The header is read first
 * and the limits checked before any pixel is decoded; a reader warning (the JDK decodes a truncated JPEG with only a
 * warning and pads it grey) counts as "cannot be resized", and the caller then serves the stored file. Readers and
 * writers are created per call and disposed, because neither is thread-safe.
 */
public class ArtworkResizer {

    /** The most pixels on either side that is decoded. */
    static final int MAX_SIDE = 4096;

    /** The most pixels in all (4 MP: twice a 1080p backdrop) that is decoded. */
    static final long MAX_PIXELS = 4_194_304L;

    /** JPEG quality of a resized copy. */
    static final float JPEG_QUALITY = 0.82f;

    /** What the header says about a stored image. */
    public enum Probe {
        /** Wider than the target and within the limits. */
        RESIZABLE,
        /** Not wider than the target: it is served as stored (never upscaled). */
        NOT_WIDER,
        /** Unreadable, too large, too deep or otherwise not safe to decode. */
        CANNOT_RESIZE
    }

    /** A header that passed the limits. */
    private record Header(int width, boolean alpha) {
    }

    /** One open reader over a file; closing it disposes the reader and closes the input. */
    private static final class Session implements AutoCloseable {
        private final ImageInputStream input;
        private final ImageReader reader;
        private boolean warned;

        /**
         * Opens the file with the reader its name's extension says (the JDK always has both). A file the JDK cannot
         * open as an image stream is a {@link NullPointerException}, which the callers treat as "cannot be resized".
         */
        private Session(Path file, String name) throws IOException {
            this.input = Objects.requireNonNull(ImageIO.createImageInputStream(file.toFile()));
            this.reader = ImageIO.getImageReadersByFormatName(isPng(name) ? "png" : "jpeg").next();
            reader.addIIOReadWarningListener((source, warning) -> warned = true);
            reader.setInput(input, true, true);
        }

        /** The header when it is within the limits, else empty. Decodes no pixel. */
        Optional<Header> header() throws IOException {
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            // No raw image type (a CMYK JPEG, say) is a NullPointerException: it cannot be resized either.
            ImageTypeSpecifier type = Objects.requireNonNull(reader.getRawImageType(0));
            if (width > MAX_SIDE || height > MAX_SIDE
                || (long) width * height > MAX_PIXELS || deeperThanEightBits(type)) {
                return Optional.empty();
            }
            return Optional.of(new Header(width, type.getColorModel().hasAlpha()));
        }

        BufferedImage read() throws IOException {
            return reader.read(0);
        }

        boolean warned() {
            return warned;
        }

        @Override
        public void close() throws IOException {
            try {
                reader.dispose();
            } finally {
                input.close();
            }
        }
    }

    /**
     * Reads only the header and says whether the image can be resized to the width.
     *
     * @param file the stored file
     * @param name the stored name; its extension says the format
     * @param targetWidth the width asked for
     * @return what the header says; never decodes a pixel
     */
    public Probe probe(Path file, String name, int targetWidth) {
        try (Session session = new Session(file, name)) {
            Optional<Header> header = session.header();
            if (header.isEmpty()) {
                return Probe.CANNOT_RESIZE;
            }
            return header.get().width() <= targetWidth ? Probe.NOT_WIDER : Probe.RESIZABLE;
        } catch (IOException | RuntimeException e) {
            return Probe.CANNOT_RESIZE;
        }
    }

    /**
     * Decodes and scales the image to the width and encodes it again in its own format.
     *
     * @param file the stored file
     * @param name the stored name; its extension says the format
     * @param targetWidth the width, narrower than the image
     * @return the encoded copy, or empty when the image cannot be resized (the limits, a reader warning, a failure)
     */
    public Optional<byte[]> resize(Path file, String name, int targetWidth) {
        try (Session session = new Session(file, name)) {
            Optional<Header> header = session.header();
            if (header.isEmpty() || header.get().width() <= targetWidth) {
                return Optional.empty();
            }
            BufferedImage source = session.read();
            if (session.warned()) {
                return Optional.empty();
            }
            boolean png = isPng(name);
            return Optional.of(encode(scale(source, targetWidth, png && header.get().alpha()), png));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** How many times to halve: while the current width is at least twice the target. */
    static int halvings(int sourceWidth, int targetWidth) {
        int count = 0;
        int width = sourceWidth;
        while (width >= 2 * targetWidth) {
            width /= 2;
            count++;
        }
        return count;
    }

    private static BufferedImage scale(BufferedImage source, int targetWidth, boolean alpha) {
        int imageType = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        int targetHeight = (int) Math.max(1, Math.round((double) source.getHeight() * targetWidth / source.getWidth()));
        BufferedImage current = source;
        for (int i = halvings(source.getWidth(), targetWidth); i > 0; i--) {
            current = draw(current, current.getWidth() / 2, Math.max(1, current.getHeight() / 2), imageType);
        }
        return draw(current, targetWidth, targetHeight, imageType);
    }

    private static BufferedImage draw(BufferedImage from, int width, int height, int imageType) {
        BufferedImage to = new BufferedImage(width, height, imageType);
        Graphics2D g = to.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(from, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return to;
    }

    private static byte[] encode(BufferedImage image, boolean png) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(png ? "png" : "jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (!png) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static boolean deeperThanEightBits(ImageTypeSpecifier type) {
        for (int bits : type.getColorModel().getComponentSize()) {
            if (bits > 8) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPng(String name) {
        return name.endsWith(".png");
    }
}
