package gregochr.cinefilm.journey;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.TestData;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PartMinutesTest {

    private static final Instant START = TestData.NOW;
    private static final long RUNTIME = 6_000_000L;

    private static List<Long> row(long minute, long median, long moved, long stalled, long ahead) {
        return List.of(minute, median, 3L, moved, stalled, ahead, 3L);
    }

    private static PartMinutes part(boolean seeked, Long endPositionMs, Long runtime, List<List<Long>> rows) {
        TreeMap<Long, List<Long>> byMinute = new TreeMap<>();
        for (List<Long> row : rows) {
            byMinute.put(row.get(0), row);
        }
        return new PartMinutes(START, seeked, endPositionMs, runtime, byMinute);
    }

    private static PartMinutes plain(List<Long> row) {
        return part(false, 3_000_000L, RUNTIME, List.of(row));
    }

    @Test
    void aMinuteHoldsItsFirstMillisecondAndNotTheNext() {
        PartMinutes part = part(false, null, null, List.of(row(0, 900, 60_000, 0, 0), row(2, 900, 60_000, 0, 0)));

        assertThat(part.rowAt(START)).isEqualTo(row(0, 900, 60_000, 0, 0));
        assertThat(part.rowAt(START.plusMillis(59_999))).isEqualTo(row(0, 900, 60_000, 0, 0));
        assertThat(part.rowAt(START.plusSeconds(60))).isNull();
        assertThat(part.rowAt(START.plusSeconds(120))).isEqualTo(row(2, 900, 60_000, 0, 0));
        assertThat(part.rowAt(START.minusMillis(1))).isNull();
    }

    @Test
    void aStallInAPlayingMinuteIsBadAtFortyFiveSecondsAndNotBelow() {
        assertThat(plain(row(3, 900, 20_000, 24_999, 0)).verdict(row(3, 900, 20_000, 24_999, 0)))
            .isEqualTo(PointState.UNKNOWN);
        assertThat(plain(row(3, 900, 20_000, 25_000, 0)).verdict(row(3, 900, 20_000, 25_000, 0)))
            .isEqualTo(PointState.BAD);
    }

    @Test
    void aStallInAPartsFirstMinuteIsNotBadAndTheMinuteIsJudgedOnItsFragment() {
        List<Long> first = row(0, 900, 20_000, 40_000, 0);

        assertThat(plain(first).verdict(first)).isEqualTo(PointState.UNKNOWN);
        List<Long> movedEnough = row(0, 900, 45_000, 15_000, 0);
        assertThat(plain(movedEnough).verdict(movedEnough)).isEqualTo(PointState.GOOD);
    }

    @Test
    void aMinuteOfStallAndNoMovementIsNotPlayingAndHasNoVerdictWhenItDidNotStallEnough() {
        List<Long> notPlaying = row(3, 900, 0, 40_000, 0);

        assertThat(plain(notPlaying).verdict(notPlaying)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aMinuteThatMovedFortyFourThousandNineHundredNinetyNineMillisecondsHasNoVerdict() {
        List<Long> short1 = row(3, 100, 44_999, 0, 0);
        List<Long> enough = row(3, 100, 45_000, 0, 0);

        assertThat(plain(short1).verdict(short1)).isEqualTo(PointState.UNKNOWN);
        assertThat(plain(enough).verdict(enough)).isEqualTo(PointState.BAD);
    }

    @Test
    void aMedianOfFourHundredNinetyNineIsBadAndFiveHundredIsGood() {
        List<Long> slow = row(3, 499, 60_000, 0, 0);
        List<Long> fine = row(3, 500, 60_000, 0, 0);

        assertThat(plain(slow).verdict(slow)).isEqualTo(PointState.BAD);
        assertThat(plain(fine).verdict(fine)).isEqualTo(PointState.GOOD);
    }

    @Test
    void aNoFragmentMinuteIsBadWellBeforeTheFilmsEnd() {
        List<Long> none = row(3, 0, 60_000, 0, 30);

        assertThat(plain(none).verdict(none)).isEqualTo(PointState.BAD);
    }

    @Test
    void theFilmsEndIsPositionPlusAheadAtTheRuntimeLessSixtySeconds() {
        List<Long> before = row(3, 0, 60_000, 0, 0);
        PartMinutes oneMsShort = part(false, RUNTIME - 60_001, RUNTIME, List.of(before));
        PartMinutes exactly = part(false, RUNTIME - 60_000, RUNTIME, List.of(before));

        assertThat(oneMsShort.verdict(before)).isEqualTo(PointState.BAD);
        assertThat(exactly.verdict(before)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void theSecondsAheadCountInMilliseconds() {
        List<Long> ahead = row(3, 0, 60_000, 0, 60);

        PartMinutes short1 = part(false, RUNTIME - 120_001, RUNTIME, List.of(ahead));
        PartMinutes exact = part(false, RUNTIME - 120_000, RUNTIME, List.of(ahead));

        assertThat(short1.verdict(ahead)).isEqualTo(PointState.BAD);
        assertThat(exact.verdict(ahead)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aMinutesPositionIsTheEndPositionLessTheLaterMinutesMovedTime() {
        List<Long> earlier = row(3, 0, 60_000, 0, 0);
        List<List<Long>> rows = List.of(earlier, row(4, 900, 50_000, 0, 0), row(5, 900, 10_000, 0, 0));

        assertThat(part(false, RUNTIME - 1, RUNTIME, rows).verdict(earlier)).isEqualTo(PointState.BAD);
        assertThat(part(false, RUNTIME, RUNTIME, rows).verdict(earlier)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void aSeekAMissingEndPositionOrAnUnknownRuntimeLeavesANoFragmentMinuteWithNoVerdict() {
        List<Long> none = row(3, 0, 60_000, 0, 30);

        assertThat(part(true, 3_000_000L, RUNTIME, List.of(none)).verdict(none)).isEqualTo(PointState.UNKNOWN);
        assertThat(part(false, null, RUNTIME, List.of(none)).verdict(none)).isEqualTo(PointState.UNKNOWN);
        assertThat(part(false, 3_000_000L, null, List.of(none)).verdict(none)).isEqualTo(PointState.UNKNOWN);
    }

    @Test
    void anOldRowWithNoSecondsAheadLeavesANoFragmentMinuteWithNoVerdict() {
        List<Long> old = List.of(3L, 0L, 3L, 60_000L, 0L);

        assertThat(plain(old).verdict(old)).isEqualTo(PointState.UNKNOWN);
    }
}
