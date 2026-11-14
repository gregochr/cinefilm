package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.credits.GreyFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContactSheetTest {

    /** Nine 4 by 2 tiles; tile {@code i} is {@code 10 * (i + 1)} everywhere except pixel (0, 0), which is 1. */
    private static List<GreyFrame> tiles() {
        List<GreyFrame> tiles = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            byte[] pixels = new byte[8];
            java.util.Arrays.fill(pixels, (byte) (10 * (i + 1)));
            pixels[0] = 1;
            tiles.add(new GreyFrame(4, 2, pixels));
        }
        return tiles;
    }

    @Test
    void theSheetIsThreeTilesWideAndHighWithFourPixelGaps() {
        GreyFrame sheet = ContactSheet.sheet(tiles());

        assertThat(sheet.width()).isEqualTo(3 * 4 + 2 * 4);
        assertThat(sheet.height()).isEqualTo(3 * 2 + 2 * 4);
    }

    @Test
    void everyGapPixelIsBlackAndEveryTilePixelIsItsSource() {
        List<GreyFrame> tiles = tiles();
        GreyFrame sheet = ContactSheet.sheet(tiles);

        for (int y = 0; y < sheet.height(); y++) {
            for (int x = 0; x < sheet.width(); x++) {
                boolean inColumnGap = x % 8 >= 4;
                boolean inRowGap = y % 6 >= 2;
                if (inColumnGap || inRowGap) {
                    assertThat(sheet.luma(x, y)).as("gap at %d,%d", x, y).isZero();
                } else {
                    GreyFrame tile = tiles.get(y / 6 * 3 + x / 8);
                    assertThat(sheet.luma(x, y)).as("tile pixel at %d,%d", x, y).isEqualTo(tile.luma(x % 8, y % 6));
                }
            }
        }
    }

    @Test
    void aSmallerTileSitsAtTheTopLeftOfItsCellAndTheRestOfTheCellIsBlack() {
        List<GreyFrame> tiles = tiles();
        tiles.set(4, new GreyFrame(3, 1, new byte[] {50, 50, 50}));

        GreyFrame sheet = ContactSheet.sheet(tiles);

        // Tile 4 is the middle cell: left 8, top 6.
        assertThat(sheet.luma(8, 6)).isEqualTo(50);
        assertThat(sheet.luma(10, 6)).isEqualTo(50);
        assertThat(sheet.luma(11, 6)).isZero();
        assertThat(sheet.luma(8, 7)).isZero();
    }

    @Test
    void thePngDecodesToTheSheet() {
        List<GreyFrame> tiles = tiles();
        GreyFrame sheet = ContactSheet.sheet(tiles);

        GreyFrame decoded = FramePrep.decodePng(ContactSheet.png(tiles));

        assertThat(decoded.width()).isEqualTo(sheet.width());
        assertThat(decoded.height()).isEqualTo(sheet.height());
        for (int y = 0; y < sheet.height(); y++) {
            for (int x = 0; x < sheet.width(); x++) {
                assertThat(decoded.luma(x, y)).isEqualTo(sheet.luma(x, y));
            }
        }
    }

    @Test
    void anythingButNineTilesIsRefused() {
        assertThatThrownBy(() -> ContactSheet.sheet(tiles().subList(0, 8)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("A contact sheet needs 9 tiles, got 8");
        List<GreyFrame> ten = new ArrayList<>(tiles());
        ten.add(ten.getFirst());
        assertThatThrownBy(() -> ContactSheet.sheet(ten)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A contact sheet needs 9 tiles, got 10");
    }
}
