package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CutListTest {

    @Test
    void theFirstCutIsZeroAndEachNextIsTheFirstKeyframeAtLeastFourSecondsLater() {
        CutList cuts = CutList.fromKeyframes(List.of(0L, 1000L, 2000L, 3999L, 4000L, 5000L, 8000L, 9000L, 12_500L),
            20_000);

        assertThat(cuts.toText()).isEqualTo("0,4000,8000,12500");
    }

    @Test
    void aKeyframeOneMillisecondShortOfTheTargetIsSkipped() {
        assertThat(CutList.fromKeyframes(List.of(3999L, 4001L), 10_000).toText()).isEqualTo("0,4001");
    }

    @Test
    void unsortedAndDuplicateKeyframesAreSortedAndDeduplicated() {
        assertThat(CutList.fromKeyframes(List.of(9000L, 4000L, 4000L, 0L), 20_000).toText()).isEqualTo("0,4000,9000");
    }

    @Test
    void aKeyframeAtOrBeyondTheDurationNeverStartsASegment() {
        assertThat(CutList.fromKeyframes(List.of(4000L, 10_000L, 11_000L), 10_000).toText()).isEqualTo("0,4000");
    }

    @Test
    void withoutKeyframesTheWholeFilmIsOneSegment() {
        CutList cuts = CutList.fromKeyframes(List.of(), 90_000);

        assertThat(cuts.segmentCount()).isEqualTo(1);
        assertThat(cuts.maxGapMs()).isEqualTo(90_000);
        assertThat(cuts.copyOk()).isFalse();
    }

    @Test
    void aZeroDurationIsRejected() {
        assertThatThrownBy(() -> CutList.fromKeyframes(List.of(), 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("duration must be positive");
        assertThatThrownBy(() -> CutList.parse("0", 0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("duration must be positive");
    }

    @Test
    void segmentStartsAndDurationsFollowTheCutsAndTheLastRunsToTheEnd() {
        CutList cuts = CutList.fromKeyframes(List.of(4004L, 8100L), 10_000);

        assertThat(cuts.segmentCount()).isEqualTo(3);
        assertThat(cuts.startMs(0)).isZero();
        assertThat(cuts.startMs(1)).isEqualTo(4004);
        assertThat(cuts.startMs(2)).isEqualTo(8100);
        assertThat(cuts.durationMs(0)).isEqualTo(4004);
        assertThat(cuts.durationMs(1)).isEqualTo(4096);
        assertThat(cuts.durationMs(2)).isEqualTo(1900);
        assertThat(cuts.totalDurationMs()).isEqualTo(10_000);
    }

    @Test
    void aSegmentOutsideTheListIsRefusedNotReadFromTheArray() {
        CutList cuts = CutList.fromKeyframes(List.of(4004L, 8100L), 10_000);

        assertThatThrownBy(() -> cuts.startMs(-1)).isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessage("No segment -1 in 3");
        assertThatThrownBy(() -> cuts.startMs(3)).isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessage("No segment 3 in 3");
        assertThatThrownBy(() -> cuts.startMs(Integer.MAX_VALUE)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> cuts.durationMs(-1)).isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessage("No segment -1 in 3");
        assertThatThrownBy(() -> cuts.durationMs(3)).isInstanceOf(IndexOutOfBoundsException.class)
            .hasMessage("No segment 3 in 3");
        assertThatThrownBy(() -> cuts.durationMs(Integer.MAX_VALUE)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theLargestGapIncludesTheFinalSegment() {
        assertThat(CutList.fromKeyframes(List.of(4000L, 8000L), 30_000).maxGapMs()).isEqualTo(22_000);
        assertThat(CutList.fromKeyframes(List.of(4000L, 20_000L), 24_000).maxGapMs()).isEqualTo(16_000);
    }

    @Test
    void copyIsOkUpToTwelveSecondsAndNotAbove() {
        assertThat(CutList.fromKeyframes(List.of(12_000L), 16_000).copyOk()).isTrue();
        assertThat(CutList.fromKeyframes(List.of(12_001L), 16_000).copyOk()).isFalse();
        assertThat(CutList.fromKeyframes(List.of(4000L), 15_999 + 4000).copyOk()).isFalse();
        assertThat(CutList.fromKeyframes(List.of(4000L), 16_000).copyOk()).isTrue();
    }

    @Test
    void textRoundTrips() {
        CutList cuts = CutList.parse("0,4004,8008", 12_000);

        assertThat(cuts.toText()).isEqualTo("0,4004,8008");
        assertThat(cuts.segmentCount()).isEqualTo(3);
        assertThat(cuts.maxGapMs()).isEqualTo(4004);
    }

    @Test
    void aSingleCutParses() {
        assertThat(CutList.parse("0", 5000).segmentCount()).isEqualTo(1);
    }

    @Test
    void corruptTextIsRejectedWithAReason() {
        assertThatThrownBy(() -> CutList.parse("", 1000)).isInstanceOf(KeyframeIndexException.class)
            .hasMessage("Stored cuts are not numbers");
        assertThatThrownBy(() -> CutList.parse("0,x", 1000)).hasMessage("Stored cuts are not numbers");
        assertThatThrownBy(() -> CutList.parse("5,10", 1000)).hasMessage("Stored cuts do not start at 0");
        assertThatThrownBy(() -> CutList.parse("0,10,10", 1000)).hasMessage("Stored cuts are not increasing");
        assertThatThrownBy(() -> CutList.parse("0,10,5", 1000)).hasMessage("Stored cuts are not increasing");
        assertThatThrownBy(() -> CutList.parse("0,1000", 1000)).hasMessage("Stored cuts run past the duration");
    }

    @Test
    void aCutJustBelowTheDurationParses() {
        assertThat(CutList.parse("0,999", 1000).durationMs(1)).isEqualTo(1);
    }

    @Test
    void segmentAtFindsTheSegmentContainingAPosition() {
        CutList cuts = CutList.parse("0,4000,8000", 12_000);

        assertThat(cuts.segmentAt(-5)).isZero();
        assertThat(cuts.segmentAt(0)).isZero();
        assertThat(cuts.segmentAt(3999)).isZero();
        assertThat(cuts.segmentAt(4000)).isEqualTo(1);
        assertThat(cuts.segmentAt(7999)).isEqualTo(1);
        assertThat(cuts.segmentAt(8000)).isEqualTo(2);
        assertThat(cuts.segmentAt(99_999)).isEqualTo(2);
    }
}
