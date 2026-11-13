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

    /** The phase 1 crawl: as {@link #crawl}, but line widths of 6 to 15 blocks that repeat every ten lines. */
    static GreyFrame crawlWithIrregularWidths(int index, int shift) {
        Canvas canvas = new Canvas(W, H, 0);
        for (int k = 0; k < 400; k++) {
            int top = 12 * k + 8 - index * shift;
            if (top >= H) {
                break;
            }
            if (top + 4 > 0) {
                canvas.line(top, 6 + k * 7 % 10, TEXT);
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
     * 11 to 61, a lit street (luma 100) over rows 62 to 78 and three bright windows. Three quarters of the picture is
     * dark (74% inside the active area), yet with the bars counted the frame is over 80% dark.
     */
    static GreyFrame letterboxedNight(int windowRow) {
        return new Canvas(W, H, 0)
            .rect(0, 11, W, 51, 30)
            .rect(0, 62, W, 17, 100)
            .rect(50, windowRow, 6, 6, 230)
            .rect(70, windowRow, 6, 6, 230)
            .rect(90, windowRow, 6, 6, 230)
            .frame();
    }

    /**
     * The same night scene with the lit street (luma 100) from row 54, so only 62% of the active area is dark: under
     * the 0.70 dark share that text on dark needs.
     */
    static GreyFrame letterboxedNightWithAWiderStreet(int windowRow) {
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
            int last = (int) Math.ceil(top + 6);
            for (int row = (int) Math.floor(top); row < last; row++) {
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

    /**
     * A card with one line of three blocks (36 bright pixels, 0.0025 of the frame) that sits {@code name} pixels
     * further right on each card: consecutive cards change 24 pixels (0.0017), two thirds of the text.
     */
    static GreyFrame creditCard(int name) {
        return new Canvas(W, H, 0).line(44, 3, TEXT, 40 + name, 3, 0).frame();
    }

    /**
     * A card with two fixed lines of three blocks (72 bright pixels) and a third line whose last block sits
     * {@code name} blocks of 5 pixels further right: consecutive cards change 24 of the 108 bright pixels.
     */
    static GreyFrame creditCardWithAFixedBody(int name) {
        return new Canvas(W, H, 0).line(20, 3, TEXT).line(30, 3, TEXT)
            .rect(40, 50, 3, 4, TEXT).rect(45, 50, 3, 4, TEXT).rect(50 + 5 * name, 50, 3, 4, TEXT).frame();
    }

    /**
     * Floors of lights on black, three windows to a floor: floor F (6x4 each, 72 pixels, always lit), floor G (6x5
     * each, 90 pixels) and floor g (5x1 each, 15 pixels).
     */
    static GreyFrame floors(boolean g90, boolean g15) {
        Canvas canvas = new Canvas(W, H, 0);
        for (int x : new int[] {40, 70, 100}) {
            canvas.rect(x, 10, 6, 4, TEXT);
            if (g90) {
                canvas.rect(x, 30, 6, 5, TEXT);
            }
            if (g15) {
                canvas.rect(x, 50, 5, 1, TEXT);
            }
        }
        return canvas.frame();
    }

    /**
     * A card of 36 pixels at luma 85 (0.36 of a peak of 235: a text pixel for the frame, but not a bright pixel for
     * the card check), with {@code light} three 4x1 lights (12 pixels at 212) that can be on or off.
     */
    static GreyFrame dimTextWithLights(boolean light) {
        Canvas canvas = new Canvas(W, H, 0).line(20, 3, 85);
        if (light) {
            for (int x : new int[] {40, 70, 100}) {
                canvas.rect(x, 50, 4, 1, 212);
            }
        }
        return canvas.frame();
    }

    /**
     * A card of 36 pixels at luma 85 with {@code pixels} pixels of dim lights (luma 100, a changed pixel and a bright
     * one for the card check) on one row, in three runs; 0 means no lights.
     */
    static GreyFrame dimTextWithDimLights(int pixels) {
        Canvas canvas = new Canvas(W, H, 0).line(20, 3, 85);
        if (pixels > 0) {
            int[] xs = {40, 70, 100};
            for (int i = 0; i < 3; i++) {
                canvas.rect(xs[i], 50, pixels / 3 + (i < pixels % 3 ? 1 : 0), 1, 100);
            }
        }
        return canvas.frame();
    }

    /** The original letterboxed night scene with a fourth window (6x6, 36 pixels) lit or dark. */
    static GreyFrame nightWithFourthWindow(boolean lit) {
        Canvas canvas = new Canvas(W, H, 0)
            .rect(0, 11, W, 51, 30)
            .rect(0, 62, W, 17, 100)
            .rect(50, 20, 6, 6, 230)
            .rect(70, 20, 6, 6, 230)
            .rect(90, 20, 6, 6, 230);
        if (lit) {
            canvas.rect(110, 20, 6, 6, 230);
        }
        return canvas.frame();
    }

    /** The original night scene with a figure of four thin strips (luma 200) {@code x} pixels from the left. */
    static GreyFrame nightWithFigure(int x) {
        Canvas canvas = new Canvas(W, H, 0)
            .rect(0, 11, W, 51, 30)
            .rect(0, 62, W, 17, 100)
            .rect(50, 20, 6, 6, 230)
            .rect(70, 20, 6, 6, 230)
            .rect(90, 20, 6, 6, 230);
        for (int strip = 0; strip < 4; strip++) {
            canvas.rect(x + 5 * strip, 40, 3, 10, 200);
        }
        return canvas.frame();
    }

    /** A copy of the frame with a rectangle drawn on it. */
    static GreyFrame withRect(GreyFrame frame, int x, int y, int rectWidth, int rectHeight, int luma) {
        Canvas canvas = new Canvas(frame.width(), frame.height(), 0);
        for (int yy = 0; yy < frame.height(); yy++) {
            for (int xx = 0; xx < frame.width(); xx++) {
                canvas.rect(xx, yy, 1, 1, frame.luma(xx, yy));
            }
        }
        return canvas.rect(x, y, rectWidth, rectHeight, luma).frame();
    }

    /** A crawl with a static grey (luma 100) pattern of alternate rows beside it, which a profile must ignore. */
    static GreyFrame crawlWithGreyStripes(int index, int shift) {
        Canvas canvas = new Canvas(W, H, 0);
        for (int y = 0; y < H; y += 2) {
            canvas.rect(5, y, 20, 1, 100);
        }
        GreyFrame crawl = crawl(index, shift);
        for (int y = 0; y < H; y++) {
            for (int x = 40; x < W; x++) {
                if (crawl.luma(x, y) > 0) {
                    canvas.rect(x, y, 1, 1, crawl.luma(x, y));
                }
            }
        }
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
