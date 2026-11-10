package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The cut lists of a split film's parts put on one timeline. */
class CutListJoinTest {

    private static final CutList PART_1 = CutList.parse("0,4000,8008", 12_000);
    private static final CutList PART_2 = CutList.parse("0,4004", 9_500);
    private static final CutList PART_3 = CutList.parse("0", 5_000);

    @Test
    void eachPartsCutsAreShiftedByWhereThePartStartsAndThePartStartIsACut() {
        CutList film = CutList.join(List.of(PART_1, PART_2, PART_3));

        assertThat(film.toText()).isEqualTo("0,4000,8008,12000,16004,21500");
        assertThat(film.segmentCount()).isEqualTo(6);
        assertThat(film.totalDurationMs()).isEqualTo(26_500);
        assertThat(film.partStartSegments()).containsExactly(0, 3, 5);
    }

    @Test
    void aPartStartsWhereThePreviousPartsVideoEndsNotAtItsLastCut() {
        CutList film = CutList.join(List.of(PART_1, PART_2));

        assertThat(film.partStartMs(0)).isZero();
        assertThat(film.partStartMs(1)).isEqualTo(12_000);
        assertThat(film.startMs(film.partStartSegment(1))).isEqualTo(12_000);
        assertThat(film.durationMs(2)).isEqualTo(3_992);
    }

    @Test
    void theLastSegmentOfEveryPartEndsAtTheNextPartsStartAndTheLastEndsAtTheFilmsEnd() {
        CutList film = CutList.join(List.of(PART_1, PART_2));

        assertThat(film.lastSegmentOfPart(0)).isEqualTo(2);
        assertThat(film.lastSegmentOfPart(1)).isEqualTo(4);
        assertThat(film.durationMs(4)).isEqualTo(5_496);
    }

    @Test
    void aSegmentBelongsToThePartItIsIn() {
        CutList film = CutList.join(List.of(PART_1, PART_2, PART_3));

        assertThat(film.partOfSegment(0)).isZero();
        assertThat(film.partOfSegment(2)).isZero();
        assertThat(film.partOfSegment(3)).isEqualTo(1);
        assertThat(film.partOfSegment(4)).isEqualTo(1);
        assertThat(film.partOfSegment(5)).isEqualTo(2);
        assertThatThrownBy(() -> film.partOfSegment(6)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> film.partOfSegment(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void eachPartKeepsItsOwnCutListOnItsOwnTimeline() {
        CutList film = CutList.join(List.of(PART_1, PART_2));

        assertThat(film.part(0)).isSameAs(PART_1);
        assertThat(film.part(1)).isSameAs(PART_2);
        assertThat(film.partCount()).isEqualTo(2);
        assertThatThrownBy(() -> film.part(2)).isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessage("No part 2 in 2");
        assertThatThrownBy(() -> film.partStartMs(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theGapAcrossAJoinCountsTowardsTheCopyLimit() {
        CutList longEnd = CutList.parse("0,4000", 4_000 + CutList.MAX_COPY_GAP_MS);
        CutList plain = CutList.parse("0", 4_000);
        assertThat(CutList.join(List.of(longEnd, plain)).copyOk()).isTrue();

        CutList tooLong = CutList.parse("0,4000", 4_000 + CutList.MAX_COPY_GAP_MS + 1);
        CutList film = CutList.join(List.of(tooLong, plain));

        assertThat(film.maxGapMs()).isEqualTo(CutList.MAX_COPY_GAP_MS + 1);
        assertThat(film.copyOk()).isFalse();
    }

    @Test
    void aSingleFilesListIsItsOwnOnlyPartAndAJoinOfOneKeepsTheSameCuts() {
        assertThat(PART_1.partCount()).isEqualTo(1);
        assertThat(PART_1.part(0)).isSameAs(PART_1);
        assertThat(PART_1.partStartSegments()).containsExactly(0);
        assertThat(PART_1.partStartMs(0)).isZero();
        assertThat(PART_1.lastSegmentOfPart(0)).isEqualTo(2);
        assertThat(PART_1.partOfSegment(2)).isZero();

        CutList one = CutList.join(List.of(PART_1));

        assertThat(one.toText()).isEqualTo(PART_1.toText());
        assertThat(one.totalDurationMs()).isEqualTo(12_000);
        assertThat(one.partCount()).isEqualTo(1);
    }

    @Test
    void joiningNothingOrAJoinedListIsRefused() {
        assertThatThrownBy(() -> CutList.join(List.of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("at least one part");
        CutList film = CutList.join(List.of(PART_1, PART_2));
        assertThatThrownBy(() -> CutList.join(List.of(film, PART_3))).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a joined list cannot be joined again");
    }

    @Test
    void aPositionIsFoundInTheJoinedListAcrossTheJoin() {
        CutList film = CutList.join(List.of(PART_1, PART_2));

        assertThat(film.segmentAt(11_999)).isEqualTo(2);
        assertThat(film.segmentAt(12_000)).isEqualTo(3);
        assertThat(film.segmentAt(16_003)).isEqualTo(3);
        assertThat(film.segmentAt(16_004)).isEqualTo(4);
    }
}
