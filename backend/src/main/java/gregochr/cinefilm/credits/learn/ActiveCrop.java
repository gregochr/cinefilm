package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;

/**
 * The active picture area, cut out of a 240 px grey frame (plan, "L3a specification", "{@code ActiveCrop}"). Pure.
 *
 * <p>The area is given in 480 px coordinates; a grey pixel {@code X} covers 480 px columns {@code 2X} and
 * {@code 2X + 1}. The crop keeps whole 2x2 boxes only.
 */
public final class ActiveCrop {

    /** The smallest crop side, in grey pixels (plan, "L3a specification", "ActiveCrop"). */
    static final int MIN_SIDE = 8;

    private final int greyWidth;
    private final int greyHeight;
    private final int x0;
    private final int x1;
    private final int y0;
    private final int y1;

    private ActiveCrop(int greyWidth, int greyHeight, int x0, int x1, int y0, int y1) {
        this.greyWidth = greyWidth;
        this.greyHeight = greyHeight;
        this.x0 = x0;
        this.x1 = x1;
        this.y0 = y0;
        this.y1 = y1;
    }

    /**
     * Builds a crop.
     *
     * @param left the area's left edge, 480 px coordinates
     * @param top the area's top edge, 480 px coordinates
     * @param width the area's width, 480 px coordinates
     * @param height the area's height, 480 px coordinates
     * @param greyWidth the grey frame's width
     * @param greyHeight the grey frame's height
     * @return the crop
     * @throws IllegalArgumentException when the area is outside the frame, or the crop is under 8 px either way
     */
    public static ActiveCrop of(int left, int top, int width, int height, int greyWidth, int greyHeight) {
        if (left < 0 || top < 0) {
            throw new IllegalArgumentException("The area starts at (" + left + ", " + top + "), before the frame");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("The area is " + width + "x" + height + ", under one pixel");
        }
        if ((long) left + width > 2L * greyWidth || (long) top + height > 2L * greyHeight) {
            throw new IllegalArgumentException("The area " + left + "," + top + " " + width + "x" + height
                + " runs past the " + 2L * greyWidth + "x" + 2L * greyHeight + " frame");
        }
        int x0 = (left + 1) / 2;
        int x1 = (left + width) / 2;
        int y0 = (top + 1) / 2;
        int y1 = (top + height) / 2;
        if (x1 - x0 < MIN_SIDE || y1 - y0 < MIN_SIDE) {
            throw new IllegalArgumentException("The crop is " + (x1 - x0) + "x" + (y1 - y0) + ", under "
                + MIN_SIDE + " px a side");
        }
        return new ActiveCrop(greyWidth, greyHeight, x0, x1, y0, y1);
    }

    /**
     * Crops a grey frame to columns {@code [x0, x1)} and rows {@code [y0, y1)}.
     *
     * @param grey the full grey frame
     * @return the cropped frame
     * @throws IllegalArgumentException when the frame is not the size this crop was made for
     */
    public GreyFrame apply(GreyFrame grey) {
        if (grey.width() != greyWidth || grey.height() != greyHeight) {
            throw new IllegalArgumentException("The frame is " + grey.width() + "x" + grey.height()
                + ", expected " + greyWidth + "x" + greyHeight);
        }
        int width = x1 - x0;
        int height = y1 - y0;
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out[y * width + x] = (byte) grey.luma(x0 + x, y0 + y);
            }
        }
        return new GreyFrame(width, height, out);
    }
}
