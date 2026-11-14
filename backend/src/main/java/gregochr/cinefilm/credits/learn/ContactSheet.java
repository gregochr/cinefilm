package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.GreyFrame;
import java.util.List;

/**
 * The contact sheet of the nine stills: the grey frames in a 3 by 3 grid with a {@value #GAP} pixel black gap between
 * tiles, written as an 8-bit greyscale PNG. No text and no fonts, because the container may have none. Pure.
 */
final class ContactSheet {

    /** The black gap between tiles, in pixels. */
    static final int GAP = 4;
    private static final int COLUMNS = 3;
    private static final int TILES = 9;

    private ContactSheet() {
    }

    /**
     * Lays the tiles out. Each tile sits at the top left of a cell as big as the largest tile; whatever a smaller tile
     * leaves of its cell is black.
     *
     * @param tiles exactly nine grey frames, in reading order
     * @return the sheet
     * @throws IllegalArgumentException when there are not nine tiles
     */
    static GreyFrame sheet(List<GreyFrame> tiles) {
        if (tiles.size() != TILES) {
            throw new IllegalArgumentException("A contact sheet needs " + TILES + " tiles, got " + tiles.size());
        }
        int cellWidth = tiles.stream().mapToInt(GreyFrame::width).max().orElseThrow();
        int cellHeight = tiles.stream().mapToInt(GreyFrame::height).max().orElseThrow();
        int width = COLUMNS * cellWidth + (COLUMNS - 1) * GAP;
        int height = COLUMNS * cellHeight + (COLUMNS - 1) * GAP;
        byte[] pixels = new byte[width * height];
        for (int i = 0; i < TILES; i++) {
            GreyFrame tile = tiles.get(i);
            int left = i % COLUMNS * (cellWidth + GAP);
            int top = i / COLUMNS * (cellHeight + GAP);
            for (int y = 0; y < tile.height(); y++) {
                for (int x = 0; x < tile.width(); x++) {
                    pixels[(top + y) * width + left + x] = (byte) tile.luma(x, y);
                }
            }
        }
        return new GreyFrame(width, height, pixels);
    }

    /**
     * The sheet as PNG bytes.
     *
     * @param tiles exactly nine grey frames, in reading order
     * @return the PNG
     */
    static byte[] png(List<GreyFrame> tiles) {
        return FramePrep.png(sheet(tiles));
    }
}
