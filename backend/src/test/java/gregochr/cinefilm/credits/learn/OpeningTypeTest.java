package gregochr.cinefilm.credits.learn;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.GreyFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpeningTypeTest {

    private static final FrameKind DARK = FrameKind.DARK;
    private static final FrameKind PLATE = FrameKind.PLATE;
    private static final FrameKind LIT = FrameKind.LIT;

    private static GreyFrame grey(int width, int height, int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new GreyFrame(width, height, bytes);
    }

    private static KindAt at(long offsetMs, FrameKind kind) {
        return new KindAt(offsetMs, kind);
    }

    // ---- frameKind from stats ------------------------------------------------------------------------------------

    @Test
    void theMedianIsDarkAtThreeAndNotAtFourOrFive() {
        assertThat(OpeningType.frameKind(130, 100, 3, 120)).isEqualTo(DARK);
        assertThat(OpeningType.frameKind(130, 100, 4, 120)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(130, 100, 5, 120)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(50, 0, 3, 200)).isEqualTo(DARK);
        assertThat(OpeningType.frameKind(50, 0, 4, 200)).isEqualTo(LIT);
        assertThat(OpeningType.frameKind(50, 0, 5, 200)).isEqualTo(LIT);
    }

    @Test
    void theMeanIsPlateFrom121AndNotBelow() {
        assertThat(OpeningType.frameKind(120.9, 100, 100, 147)).isEqualTo(LIT);
        assertThat(OpeningType.frameKind(121, 100, 100, 147)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(122, 100, 100, 147)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(120.0, 100, 100, 147)).isEqualTo(LIT);
    }

    @Test
    void theSpreadIsPlateUpTo47AndNotAbove() {
        assertThat(OpeningType.frameKind(150, 100, 120, 146)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(150, 100, 120, 147)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(150, 100, 120, 148)).isEqualTo(LIT);
    }

    @Test
    void darkWinsOverPlate() {
        assertThat(OpeningType.frameKind(200, 3, 3, 3)).isEqualTo(DARK);
    }

    // ---- frameKind from a frame ----------------------------------------------------------------------------------

    @Test
    void aFrameReadsTheNearestRankPercentilesOfTenPixels() {
        // Sorted: 100 110 115 120 125 130 135 140 147 255. With n = 10 the ranks are 1, 5 and 9, so P10 = 100 and
        // P90 = 147 (the spread is exactly 47); the 255 is rank 10 and is not P90. The mean is 137.7.
        GreyFrame plate = grey(5, 2, 255, 100, 110, 115, 120, 125, 130, 135, 140, 147);

        assertThat(OpeningType.frameKind(plate)).isEqualTo(PLATE);
    }

    @Test
    void aFrameWhoseSpreadIs48IsLit() {
        // The same, with the lowest pixel at 99: P10 = 99, P90 = 147, spread 48.
        GreyFrame lit = grey(5, 2, 255, 99, 110, 115, 120, 125, 130, 135, 140, 147);

        assertThat(OpeningType.frameKind(lit)).isEqualTo(LIT);
    }

    @Test
    void aFrameWhoseMeanIsExactly121IsPlateAndOneBelowIsLit() {
        GreyFrame exact = grey(5, 2, 121, 121, 121, 121, 121, 121, 121, 121, 121, 121);
        // Sum 1209 over 10 pixels: mean 120.9.
        GreyFrame below = grey(5, 2, 120, 121, 121, 121, 121, 121, 121, 121, 121, 121);

        assertThat(OpeningType.frameKind(exact)).isEqualTo(PLATE);
        assertThat(OpeningType.frameKind(below)).isEqualTo(LIT);
    }

    @Test
    void aFrameReadsTheMedianAtRankFiveOfTen() {
        // Sorted: 1 1 1 1 3 200 200 200 200 200. The median is the 5th value, 3: DARK.
        GreyFrame dark = grey(5, 2, 200, 1, 200, 1, 200, 1, 200, 1, 200, 3);
        // The same with the 5th value 4: LIT (mean 100.8, spread 199).
        GreyFrame notDark = grey(5, 2, 200, 1, 200, 1, 200, 1, 200, 1, 200, 4);

        assertThat(OpeningType.frameKind(dark)).isEqualTo(DARK);
        assertThat(OpeningType.frameKind(notDark)).isEqualTo(LIT);
    }

    @Test
    void aThreePixelFrameTakesRanks1To3AndAOnePixelFrameTheOnlyValue() {
        // n = 3: ranks (30 + 99) / 100 = 1, (150 + 99) / 100 = 2, (270 + 99) / 100 = 3. Median 3: DARK.
        assertThat(OpeningType.frameKind(grey(3, 1, 255, 0, 3))).isEqualTo(DARK);
        // The median is the middle value, 200, not the smallest or largest.
        assertThat(OpeningType.frameKind(grey(3, 1, 255, 0, 200))).isEqualTo(LIT);
        assertThat(OpeningType.frameKind(grey(1, 1, 3))).isEqualTo(DARK);
        assertThat(OpeningType.frameKind(grey(1, 1, 4))).isEqualTo(LIT);
        assertThat(OpeningType.frameKind(grey(1, 1, 121))).isEqualTo(PLATE);
    }

    // ---- OpeningType.of ------------------------------------------------------------------------------------------

    @Test
    void anEmptyListGivesEmpty() {
        assertThat(OpeningType.of(List.of(), 0)).isEmpty();
    }

    @Test
    void noEntryAtOrAfterTheStartGivesEmpty() {
        assertThat(OpeningType.of(List.of(at(999, PLATE)), 1000)).isEmpty();
        assertThat(OpeningType.of(List.of(at(0, PLATE), at(500, PLATE), at(999, DARK)), 1000)).isEmpty();
    }

    @Test
    void anEntryExactlyAtTheStartCounts() {
        assertThat(OpeningType.of(List.of(at(1000, PLATE)), 1000)).contains(PLATE);
        assertThat(OpeningType.of(List.of(at(1000, DARK)), 1000)).contains(DARK);
        assertThat(OpeningType.of(List.of(at(1000, LIT)), 1000)).contains(LIT);
    }

    @Test
    void aDarkFiveSecondFrameGivesDarkEvenWhenALaterOneIsAPlate() {
        List<KindAt> coarse = List.of(at(5000, DARK), at(20000, PLATE), at(45000, PLATE));

        assertThat(OpeningType.of(coarse, 0)).contains(DARK);
    }

    @Test
    void aDarkFrameAtTwentyOrFortyFiveSecondsDoesNotMakeItDark() {
        List<KindAt> coarse = List.of(at(5000, LIT), at(20000, DARK), at(45000, DARK));

        assertThat(OpeningType.of(coarse, 0)).contains(LIT);
    }

    @Test
    void aPlateAtAnyOfTheThreeTimesGivesPlate() {
        assertThat(OpeningType.of(List.of(at(5000, PLATE), at(20000, LIT), at(45000, LIT)), 0)).contains(PLATE);
        assertThat(OpeningType.of(List.of(at(5000, LIT), at(20000, PLATE), at(45000, LIT)), 0)).contains(PLATE);
        assertThat(OpeningType.of(List.of(at(5000, LIT), at(20000, LIT), at(45000, PLATE)), 0)).contains(PLATE);
    }

    @Test
    void allLitGivesLit() {
        assertThat(OpeningType.of(List.of(at(5000, LIT), at(20000, LIT), at(45000, LIT)), 0)).contains(LIT);
    }

    @Test
    void theTimesAreRelativeToTheStart() {
        // Start 10 s: the looks are at 15, 30 and 55 s. The entries at 5, 20 and 45 s are not the ones nearest.
        List<KindAt> coarse = List.of(at(15000, LIT), at(30000, LIT), at(55000, PLATE));

        assertThat(OpeningType.of(coarse, 10000)).contains(PLATE);
        assertThat(OpeningType.of(List.of(at(15000, DARK), at(30000, LIT), at(55000, LIT)), 10000)).contains(DARK);
    }

    @Test
    void theNearestEntryIsPickedNotTheFirstOrTheLast() {
        // Target 5 s: 5.5 s (LIT) is nearer than 4 s (DARK); target 20 s: 21 s (PLATE) is nearer than 30 s (LIT).
        List<KindAt> coarse = List.of(at(0, DARK), at(4000, DARK), at(5500, LIT), at(21000, PLATE), at(30000, LIT));

        assertThat(OpeningType.of(coarse, 0)).contains(PLATE);
        // Target 5 s: 4 s (DARK) is nearer than 8 s (LIT).
        assertThat(OpeningType.of(List.of(at(4000, DARK), at(8000, LIT)), 0)).contains(DARK);
        // The first entry is exactly on target and a later one is farther: the first stays.
        assertThat(OpeningType.of(List.of(at(5000, DARK), at(6000, LIT)), 0)).contains(DARK);
    }

    @Test
    void anEntryBeforeTheStartCanBeNearestToALook() {
        // Start 10 s, look at 15 s: 14 s (DARK) is nearer than 20 s (LIT). The last entry is after the start.
        assertThat(OpeningType.of(List.of(at(14000, DARK), at(20000, LIT)), 10000)).contains(DARK);
    }

    @Test
    void onATieTheEarlierEntryWinsAtTheFiveSecondLook() {
        // Target 5 s: 4 s and 6 s are both 1 s away.
        assertThat(OpeningType.of(List.of(at(4000, DARK), at(6000, LIT)), 0)).contains(DARK);
        assertThat(OpeningType.of(List.of(at(4000, LIT), at(6000, DARK)), 0)).contains(LIT);
    }

    @Test
    void onATieTheEarlierEntryWinsAtTheTwentyAndFortyFiveSecondLooks() {
        // The 5 s look is a LIT frame at 5 s; the 20 s look ties between 19 s and 21 s; the 45 s look is LIT at 45 s.
        assertThat(OpeningType.of(
            List.of(at(5000, LIT), at(19000, PLATE), at(21000, LIT), at(45000, LIT)), 0)).contains(PLATE);
        assertThat(OpeningType.of(
            List.of(at(5000, LIT), at(19000, LIT), at(21000, PLATE), at(45000, LIT)), 0)).contains(LIT);
        // The 45 s look ties between 44 s and 46 s.
        assertThat(OpeningType.of(
            List.of(at(5000, LIT), at(20000, LIT), at(44000, PLATE), at(46000, LIT)), 0)).contains(PLATE);
        assertThat(OpeningType.of(
            List.of(at(5000, LIT), at(20000, LIT), at(44000, LIT), at(46000, PLATE)), 0)).contains(LIT);
    }
}
