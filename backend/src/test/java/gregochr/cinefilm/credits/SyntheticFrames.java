package gregochr.cinefilm.credits;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the synthetic frames the credits tests use: 160x90 greyscale pictures of black, text lines, crawls, cards,
 * a starfield, a letterboxed night scene and plain pictures. Text is rows of 3-pixel-wide blocks 5 pixels apart, four
 * rows tall.
 */
final class SyntheticFrames {

    static final int W = 160;
    static final int H = 90;
    static final int TEXT = 235;
    /** Pixels in one frame. */
    static final double PIXELS = W * H;

    private SyntheticFrames() {
    }

    /** A drawing surface that becomes a {@link GreyFrame}. */
    static final class Canvas {
        private final int width;
        private final int height;
        private final byte[] pixels;

        Canvas(int width, int height, int background) {
            this.width = width;
            this.height = height;
            this.pixels = new byte[width * height];
            java.util.Arrays.fill(pixels, (byte) background);
        }

        /** Fills a rectangle, clipped to the canvas. */
        Canvas rect(int x, int y, int rectWidth, int rectHeight, int luma) {
            for (int yy = Math.max(0, y); yy < Math.min(height, y + rectHeight); yy++) {
                for (int xx = Math.max(0, x); xx < Math.min(width, x + rectWidth); xx++) {
                    pixels[yy * width + xx] = (byte) luma;
                }
            }
            return this;
        }

        /** A line of text: {@code blocks} blocks of {@code blockWidth} pixels from {@code left}, 4 rows tall. */
        Canvas line(int top, int blocks, int luma, int left, int blockWidth, int halo) {
            for (int k = 0; k < blocks; k++) {
                int x = left + (blockWidth + 2) * k;
                if (halo > 0) {
                    rect(x - 1, top - 1, blockWidth + 2, 6, halo);
                }
                rect(x, top, blockWidth, 4, luma);
            }
            return this;
        }

        Canvas line(int top, int blocks, int luma) {
            return line(top, blocks, luma, 40, 3, 0);
        }

        GreyFrame frame() {
            return new GreyFrame(width, height, pixels);
        }
    }

    static GreyFrame fill(int luma) {
        return new Canvas(W, H, luma).frame();
    }

    static GreyFrame black() {
        return fill(0);
    }

    /** A mid-tone picture: the film itself. */
    static GreyFrame picture() {
        return fill(128);
    }

    /** A colourful title sequence: a mid-tone picture with bright patches. */
    static GreyFrame title() {
        return new Canvas(W, H, 90).rect(10, 10, 30, 20, 200).rect(70, 40, 30, 20, 200).rect(110, 60, 30, 20, 200)
            .frame();
    }

    /** Text lines of {@code blocks} blocks each at the given tops, on a background. */
    static GreyFrame lines(int background, int luma, int blocks, int... tops) {
        Canvas canvas = new Canvas(W, H, background);
        for (int top : tops) {
            canvas.line(top, blocks, luma);
        }
        return canvas.frame();
    }

    /** A static card of white text on black; each variant 0 to 4 has its lines in different places. */
    static GreyFrame card(int variant) {
        return new Canvas(W, H, 0)
            .line(4 + 6 * variant, 10 + 2 * variant, TEXT)
            .line(32 + 4 * variant, 10 + 2 * variant, TEXT)
            .line(60 - 3 * variant, 10 + 2 * variant, TEXT)
            .line(80 - 4 * variant, 10 + 2 * variant, TEXT)
            .frame();
    }

    /** A sparse card: two short lines (about 1.3% bright); each variant 0 to 2 puts them in other places. */
    static GreyFrame sparseCard(int variant) {
        return new Canvas(W, H, 0)
            .line(20 + 10 * variant, 8, TEXT)
            .line(60 - 8 * variant, 8, TEXT)
            .frame();
    }

    /** A card with a single short name: 8 blocks, 96 bright pixels (about 0.67%). */
    static GreyFrame oneLineCard() {
        return new Canvas(W, H, 0).line(44, 8, TEXT).frame();
    }

    /**
     * The width of crawl line {@code k}, in blocks: wide and narrow lines alternate, so no shift smaller than two
     * line spacings lines a line up with another of the same width (a shift that is not the real one never matches).
     */
    private static int blocksOfLine(int k) {
        return k % 2 == 0 ? 5 : 18;
    }

    /** One frame of a crawl: lines rise {@code shift} pixels a frame; every line has its own width. */
    static GreyFrame crawl(int index, int shift) {
        Canvas canvas = new Canvas(W, H, 0);
        for (int k = 0; k < 400; k++) {
            int top = 12 * k + 8 - index * shift;
            if (top >= H) {
                break;
            }
            if (top + 4 > 0) {
                canvas.line(top, blocksOfLine(k), TEXT);
            }
        }
        return canvas.frame();
    }

    /** A crawl whose speed alternates between two values, so it is not steady. */
    static List<GreyFrame> unsteadyCrawl(int frames, int slow, int fast) {
        List<GreyFrame> out = new ArrayList<>();
        int travelled = 0;
        for (int i = 0; i < frames; i++) {
            out.add(crawl(travelled, 1));
            travelled += i % 2 == 0 ? slow : fast;
        }
        return out;
    }

    /** Scattered single bright pixels on near-black: a starfield. */
    static GreyFrame starfield() {
        Canvas canvas = new Canvas(W, H, 5);
        Random random = new Random(7);
        for (int i = 0; i < 43; i++) {
            canvas.rect(random.nextInt(W), random.nextInt(H), 1, 1, 230);
        }
        return canvas.frame();
    }

    /**
     * A 2.39:1 night scene in a 16:9 frame: black bars on rows 0 to 10 and 79 to 89, a dark sky (luma 30) over rows
     * 11 to 53, a lit street (luma 100) over rows 54 to 78 and three bright windows. About 62% of the picture is
     * dark, yet with the bars counted the frame is over 70% dark.
     */
    static GreyFrame letterboxedNight(int windowRow) {
        return new Canvas(W, H, 0)
            .rect(0, 11, W, 43, 30)
            .rect(0, 54, W, 25, 100)
            .rect(50, windowRow, 6, 6, 230)
            .rect(70, windowRow, 6, 6, 230)
            .rect(90, windowRow, 6, 6, 230)
            .frame();
    }

    /** One frame of a crawl that enters from the bottom: the first line is only just in view at index 0. */
    static GreyFrame crawlFromBottom(int index, int shift) {
        Canvas canvas = new Canvas(W, H, 0);
        for (int k = 0; k < 400; k++) {
            int top = H - 4 + 12 * k - index * shift;
            if (top >= H) {
                break;
            }
            if (top + 4 > 0) {
                canvas.line(top, blocksOfLine(k), TEXT);
            }
        }
        return canvas.frame();
    }

    /**
     * One frame of a crawl whose small, anti-aliased text is noisy: each text pixel is bright with probability
     * {@code 1 - dim} and otherwise only dim (90), as letter edges are when the text sits between two pixel rows. The
     * noise is seeded by the frame index, so a frame is always the same.
     */
    static GreyFrame noisyCrawl(int index, int shift, double dim) {
        GreyFrame clean = crawl(index, shift);
        Random random = new Random(index * 31L + 7);
        Canvas canvas = new Canvas(W, H, 0);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (clean.luma(x, y) > 0) {
                    canvas.rect(x, y, 1, 1, random.nextDouble() < dim ? 90 : TEXT);
                }
            }
        }
        return canvas.frame();
    }

    /**
     * Three rows of four lit windows seen at night, tilting up a quarter of a pixel a frame; edges are anti-aliased,
     * so the picture drifts without any pixel jumping.
     */
    static GreyFrame tiltedWindows(int index) {
        Canvas canvas = new Canvas(W, H, 12);
        double shift = index * 0.25;
        for (int rowTop : new int[] {20, 40, 60}) {
            double top = rowTop - shift;
            for (int row = (int) Math.floor(top); row < top + 6; row++) {
                double coverage = Math.min(row + 1, top + 6) - Math.max(row, top);
                int luma = 12 + (int) Math.round((TEXT - 12) * Math.max(0, Math.min(1, coverage)));
                for (int column = 0; column < 4; column++) {
                    canvas.rect(40 + 20 * column, row, 5, 1, luma);
                }
            }
        }
        return canvas.frame();
    }

    /** A still dark room with a small figure made of four thin bright strips, {@code x} pixels from the left. */
    static GreyFrame figure(int x) {
        Canvas canvas = new Canvas(W, H, 8);
        for (int strip = 0; strip < 4; strip++) {
            canvas.rect(x + 5 * strip, 40, 3, 10, 200);
        }
        return canvas.frame();
    }

    /** A card with two fixed lines and one that shows {@code name} blocks, each a different arrangement. */
    static GreyFrame creditCard(int name) {
        Canvas canvas = new Canvas(W, H, 0).line(20, 3, TEXT).line(30, 3, TEXT);
        // The third line keeps blocks at 40 and 45 and puts the last one at 50 + 5 * name: 24 pixels change.
        canvas.rect(40, 50, 3, 4, TEXT).rect(45, 50, 3, 4, TEXT).rect(50 + 5 * name, 50, 3, 4, TEXT);
        return canvas.frame();
    }

    /** Builds a list of dense frames, two a second. */
    static final class DenseBuilder {
        private final List<GreyFrame> frames = new ArrayList<>();

        DenseBuilder add(GreyFrame frame, int count) {
            for (int i = 0; i < count; i++) {
                frames.add(frame);
            }
            return this;
        }

        DenseBuilder crawl(int count, int shift) {
            for (int i = 0; i < count; i++) {
                frames.add(SyntheticFrames.crawl(i, shift));
            }
            return this;
        }

        /** Cards 0 to {@code cards - 1}, each shown for {@code each} frames. */
        DenseBuilder cards(int cards, int each) {
            for (int c = 0; c < cards; c++) {
                add(card(c), each);
            }
            return this;
        }

        List<GreyFrame> frames() {
            return List.copyOf(frames);
        }

        List<DenseFrame> build(long firstOffsetMs) {
            List<DenseFrame> out = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                out.add(new DenseFrame(firstOffsetMs + 500L * i, frames.get(i)));
            }
            return out;
        }
    }
}
