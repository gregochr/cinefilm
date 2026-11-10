package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.EditionTimeline.Position;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditionTimelineTest {

    /** Part 1 is 0..1000, part 2 is 1000..3000, part 3 is 3000..3500. */
    private final EditionTimeline timeline = EditionTimeline.of(List.of(1_000L, 2_000L, 500L));

    @Test
    void totalsStartsAndPartCountComeFromTheDurationsInOrder() {
        assertThat(timeline.totalMs()).isEqualTo(3_500L);
        assertThat(timeline.partCount()).isEqualTo(3);
        assertThat(timeline.partStartMs(0)).isZero();
        assertThat(timeline.partStartMs(1)).isEqualTo(1_000L);
        assertThat(timeline.partStartMs(2)).isEqualTo(3_000L);
    }

    @Test
    void aSinglePartIsTheIdentity() {
        EditionTimeline single = EditionTimeline.of(List.of(5_000L));

        assertThat(single.totalMs()).isEqualTo(5_000L);
        assertThat(single.locate(0)).isEqualTo(new Position(0, 0));
        assertThat(single.locate(1_234)).isEqualTo(new Position(0, 1_234));
        assertThat(single.locate(4_999)).isEqualTo(new Position(0, 4_999));
        assertThat(single.toGlobal(0, 1_234)).isEqualTo(1_234L);
    }

    @Test
    void locateFindsThePartAndOffsetInsideIt() {
        assertThat(timeline.locate(1)).isEqualTo(new Position(0, 1));
        assertThat(timeline.locate(999)).isEqualTo(new Position(0, 999));
        assertThat(timeline.locate(1_001)).isEqualTo(new Position(1, 1));
        assertThat(timeline.locate(2_999)).isEqualTo(new Position(1, 1_999));
        assertThat(timeline.locate(3_001)).isEqualTo(new Position(2, 1));
        assertThat(timeline.locate(3_499)).isEqualTo(new Position(2, 499));
    }

    @Test
    void aTimeExactlyOnAJoinBelongsToTheLaterPartAtOffsetZero() {
        assertThat(timeline.locate(1_000)).isEqualTo(new Position(1, 0));
        assertThat(timeline.locate(3_000)).isEqualTo(new Position(2, 0));
    }

    @Test
    void negativeTimeClampsToTheStartOfPartOne() {
        assertThat(timeline.locate(-1)).isEqualTo(new Position(0, 0));
        assertThat(timeline.locate(Long.MIN_VALUE)).isEqualTo(new Position(0, 0));
        assertThat(timeline.locate(0)).isEqualTo(new Position(0, 0));
    }

    @Test
    void theTotalAndBeyondIsTheLastPartAtItsFullDuration() {
        assertThat(timeline.locate(3_499)).isEqualTo(new Position(2, 499));
        assertThat(timeline.locate(3_500)).isEqualTo(new Position(2, 500));
        assertThat(timeline.locate(3_501)).isEqualTo(new Position(2, 500));
        assertThat(timeline.locate(Long.MAX_VALUE)).isEqualTo(new Position(2, 500));
    }

    @Test
    void toGlobalAddsThePartStart() {
        assertThat(timeline.toGlobal(0, 0)).isZero();
        assertThat(timeline.toGlobal(1, 0)).isEqualTo(1_000L);
        assertThat(timeline.toGlobal(1, 1_999)).isEqualTo(2_999L);
        assertThat(timeline.toGlobal(2, 250)).isEqualTo(3_250L);
    }

    @Test
    void toGlobalAcceptsTheFullDurationOfAPartButNotOneMoreNorLessThanZero() {
        assertThat(timeline.toGlobal(0, 1_000)).isEqualTo(1_000L);
        assertThat(timeline.toGlobal(2, 500)).isEqualTo(3_500L);
        assertThatThrownBy(() -> timeline.toGlobal(0, 1_001)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Offset 1001 is outside part 1 (0 to 1000)");
        assertThatThrownBy(() -> timeline.toGlobal(2, 501)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Offset 501 is outside part 3 (0 to 500)");
        assertThatThrownBy(() -> timeline.toGlobal(1, -1)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Offset -1 is outside part 2 (0 to 2000)");
    }

    @Test
    void anUnknownPartIsRejected() {
        assertThatThrownBy(() -> timeline.toGlobal(3, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No part 3 in a timeline of 3 parts");
        assertThatThrownBy(() -> timeline.toGlobal(-1, 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No part -1 in a timeline of 3 parts");
        assertThatThrownBy(() -> timeline.partStartMs(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> timeline.partStartMs(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyDurationMustBeAboveZero() {
        assertThatThrownBy(() -> EditionTimeline.of(List.of(1_000L, 0L)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Part 2 needs a duration above 0, was 0");
        assertThatThrownBy(() -> EditionTimeline.of(List.of(-5L)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Part 1 needs a duration above 0, was -5");
        assertThatThrownBy(() -> EditionTimeline.of(Arrays.asList(1_000L, null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Part 2 needs a duration above 0, was null");
        assertThat(EditionTimeline.of(List.of(1L)).totalMs()).isEqualTo(1L);
    }

    @Test
    void aTimelineNeedsAtLeastOnePart() {
        assertThatThrownBy(() -> EditionTimeline.of(List.<Long>of())).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A timeline needs at least one part");
        assertThatThrownBy(() -> EditionTimeline.of((List<Long>) null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("A timeline needs at least one part");
    }

    @Test
    void ofAnEditionUsesItsOriginalPartsInPartOrderAndIgnoresPremadeRenditions() {
        Edition edition = edition(film(1L, "Towers", 2002, NOW), 10L, "Extended", 0, null);
        MediaFile second = file(edition, "Towers pt2.m4v");
        second.setPartNumber(2);
        second.setDurationMs(2_000L);
        MediaFile first = file(edition, "Towers pt1.m4v");
        first.setDurationMs(1_000L);
        MediaFile premade = file(edition, "Towers-720.mp4");
        premade.setKind(MediaKind.PREMADE_720);
        premade.setDurationMs(99_000L);

        EditionTimeline built = EditionTimeline.of(edition);

        assertThat(built.partCount()).isEqualTo(2);
        assertThat(built.partStartMs(1)).isEqualTo(1_000L);
        assertThat(built.totalMs()).isEqualTo(3_000L);
    }

    @Test
    void ofAnEditionWithAPartLackingADurationIsRejected() {
        Edition edition = edition(film(1L, "Towers", 2002, NOW), 10L, "Extended", 0, null);
        file(edition, "Towers.m4v").setDurationMs(null);

        assertThatThrownBy(() -> EditionTimeline.of(edition)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Part 1 needs a duration above 0, was null");
    }
}
