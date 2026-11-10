package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.StateCarry.Old;
import gregochr.cinefilm.library.StateCarry.Piece;
import gregochr.cinefilm.library.StateCarry.Result;
import gregochr.cinefilm.library.StateFolding.State;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link StateCarry}: state and credits markers following the files they were in. */
class StateCarryTest {

    private static final Map<Long, Long> TIMES = Map.of(1L, 100 * MIN, 2L, 50 * MIN, 3L, 0L);

    private static State resume(long positionMs) {
        return new State(positionMs, false, null, NOW, false, null);
    }

    private static State watched() {
        return new State(0, true, NOW, NOW, false, null);
    }

    private static Old ab(State state, Long marker) {
        return new Old(List.of(1L, 2L), List.of(100 * MIN, 50 * MIN), state, marker);
    }

    @Test
    void aPositionIsPlacedByTheFileItWasInAndItsOffsetThere() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(resume(120 * MIN), null)),
            Map.of(1L, 8L, 2L, 9L));

        assertThat(pieces).containsOnlyKeys(9L);
        assertThat(pieces.get(9L)).containsExactly(new Piece(resume(20 * MIN), List.of(2L), null, null));
    }

    @Test
    void swappingTheOrderOfTheFilesKeepsThePositionInItsFile() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(resume(120 * MIN), null)),
            Map.of(1L, 9L, 2L, 9L));

        Result result = StateCarry.land(pieces.get(9L), List.of(2L, 1L), TIMES);

        assertThat(result.state().positionMs()).as("20 min into file 2, which now comes first").isEqualTo(20 * MIN);
    }

    @Test
    void aPositionExactlyOnAJoinIsProgressAtTheEndOfTheEarlierFile() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(resume(100 * MIN), null)),
            Map.of(1L, 9L, 2L, 9L));

        assertThat(pieces.get(9L)).containsExactly(new Piece(resume(100 * MIN), List.of(1L), null, null));
        assertThat(StateCarry.land(pieces.get(9L), List.of(1L, 2L), TIMES).state().positionMs())
            .as("unchanged").isEqualTo(100 * MIN);
        assertThat(StateCarry.land(pieces.get(9L), List.of(2L, 1L), TIMES).state().positionMs())
            .as("swapped: the end of file 1, which now comes last").isEqualTo(150 * MIN);
    }

    @Test
    void anUnstartedEditionStaysUnstarted() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(resume(0), null)), Map.of(1L, 9L, 2L, 9L));

        assertThat(pieces.get(9L)).containsExactly(new Piece(resume(0), List.of(1L), null, null));
        assertThat(StateCarry.land(pieces.get(9L), List.of(1L, 2L), TIMES).state().positionMs()).isZero();
    }

    @Test
    void filesInterleavedFromTwoEditionsEachBringTheirOwnPosition() {
        Old other = new Old(List.of(3L), List.of(80 * MIN), new State(10 * MIN, false, null, NOW.minusSeconds(60),
            false, null), null);
        Map<Long, Long> times = Map.of(1L, 100 * MIN, 2L, 50 * MIN, 3L, 80 * MIN);

        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(resume(120 * MIN), null), other),
            Map.of(1L, 9L, 2L, 9L, 3L, 9L));
        Result result = StateCarry.land(pieces.get(9L), List.of(1L, 3L, 2L), times);

        assertThat(result.state().positionMs()).as("file 2 now starts at 180 min, and the later play wins")
            .isEqualTo(200 * MIN);
    }

    @Test
    void aMarkerIsPlacedByItsFileAndKeptOnlyInTheLastFile() {
        Old other = new Old(List.of(3L), List.of(80 * MIN), null, 70 * MIN);
        Map<Long, Long> times = Map.of(1L, 100 * MIN, 2L, 50 * MIN, 3L, 80 * MIN);
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(other), Map.of(3L, 9L));

        assertThat(pieces.get(9L)).containsExactly(new Piece(null, List.of(3L), 3L, 70 * MIN));
        assertThat(StateCarry.land(pieces.get(9L), List.of(1L, 2L, 3L), times))
            .isEqualTo(new Result(null, 220 * MIN));
        assertThat(StateCarry.land(pieces.get(9L), List.of(1L, 3L, 2L), times)).isEqualTo(new Result(null, null));
    }

    @Test
    void aWatchedEditionIsWatchedForTheFilesItHadWhereItsLastFileWent() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(watched(), null)), Map.of(1L, 8L, 2L, 9L));

        assertThat(pieces).containsOnlyKeys(9L);
        assertThat(pieces.get(9L)).containsExactly(new Piece(watched(), List.of(2L), null, null));
        assertThat(StateCarry.land(pieces.get(9L), List.of(2L), TIMES).state().watched()).isTrue();
    }

    @Test
    void aWatchedPartBeforeTheLastBecomesAResumeAtTheStartOfTheNext() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(watched(), null)), Map.of(1L, 9L, 2L, 9L));
        Map<Long, List<Piece>> first = Map.of(9L, List.of(new Piece(watched(), List.of(1L), null, null)));

        assertThat(StateCarry.land(pieces.get(9L), List.of(1L, 2L), TIMES).state().watched()).isTrue();
        assertThat(StateCarry.land(first.get(9L), List.of(1L, 2L), TIMES).state().positionMs())
            .isEqualTo(100 * MIN);
    }

    @Test
    void whatWasInAFileThatLeavesTheFilmLeavesWithIt() {
        HashMap<Long, Long> targets = new HashMap<>();
        targets.put(1L, 8L);
        targets.put(2L, null);

        assertThat(StateCarry.split(List.of(ab(resume(120 * MIN), 140 * MIN)), targets)).isEmpty();
        assertThat(StateCarry.split(List.of(ab(watched(), null)), targets)).isEmpty();
    }

    @Test
    void theEditionsThatReceiveFilesFromAnUncarriableEditionAreReported() {
        Old unknown = new Old(List.of(1L, 3L), Arrays.asList(100 * MIN, null), resume(40 * MIN), null);
        Old markerOnly = new Old(List.of(4L), Arrays.asList((Long) null), null, MIN);
        Old nothing = new Old(List.of(5L), Arrays.asList((Long) null), null, null);
        Old fine = ab(resume(MIN), null);
        HashMap<Long, Long> targets = new HashMap<>();
        targets.put(1L, 8L);
        targets.put(3L, null);
        targets.put(4L, 7L);
        targets.put(5L, 6L);
        targets.put(2L, 9L);

        assertThat(StateCarry.uncarried(List.of(unknown, markerOnly, nothing, fine), targets))
            .containsExactlyInAnyOrder(8L, 7L);
    }

    private static State rewatch(long positionMs, java.time.Instant lastPlayed) {
        return new State(positionMs, true, NOW.minusSeconds(3600), lastPlayed, false, null);
    }

    @Test
    void aWatchedEditionBeingWatchedAgainCarriesItsPositionBesideTheWatchedFlag() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(rewatch(120 * MIN, NOW), null)),
            Map.of(1L, 9L, 2L, 9L));

        assertThat(pieces.get(9L)).hasSize(2);
        assertThat(pieces.get(9L).get(1)).isEqualTo(new Piece(rewatch(20 * MIN, NOW), List.of(2L), null, null, true));
        Result swapped = StateCarry.land(pieces.get(9L), List.of(2L, 1L), TIMES);
        assertThat(swapped.state().watched()).isTrue();
        assertThat(swapped.state().positionMs()).isEqualTo(20 * MIN);
        assertThat(swapped.state().watchedAt()).isEqualTo(NOW.minusSeconds(3600));
    }

    @Test
    void theLatestRewatchPositionWinsAndANotWatchedResultKeepsNoRewatch() {
        Piece older = new Piece(rewatch(10 * MIN, NOW.minusSeconds(60)), List.of(1L), null, null, true);
        Piece newer = new Piece(rewatch(5 * MIN, NOW), List.of(2L), null, null, true);
        Piece unplayed = new Piece(rewatch(1 * MIN, null), List.of(1L), null, null, true);
        Piece watchedAll = new Piece(rewatch(0, NOW), List.of(1L, 2L), null, null);

        State both = StateCarry.land(List.of(watchedAll, older, newer, unplayed), List.of(1L, 2L), TIMES).state();
        State partOnly = StateCarry.land(List.of(new Piece(rewatch(0, NOW), List.of(1L), null, null), older),
            List.of(1L, 2L), TIMES).state();

        assertThat(both.watched()).isTrue();
        assertThat(both.positionMs()).as("the later play: 5 min into file 2").isEqualTo(105 * MIN);
        assertThat(partOnly.watched()).as("only part 1 was watched").isFalse();
        assertThat(partOnly.positionMs()).isEqualTo(100 * MIN);
    }

    @Test
    void anUnwatchedEditionBehavesAsBefore() {
        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(ab(resume(120 * MIN), null)),
            Map.of(1L, 9L, 2L, 9L));

        assertThat(pieces.get(9L)).containsExactly(new Piece(resume(20 * MIN), List.of(2L), null, null));
        assertThat(pieces.get(9L).get(0).rewatch()).isFalse();
    }

    @Test
    void anEditionWithNothingToCarryAddsNoPiece() {
        assertThat(StateCarry.split(List.of(ab(null, null)), Map.of(1L, 8L, 2L, 9L))).isEmpty();
    }

    @Test
    void anEditionWithAFileOfUnknownDurationIsNotCarried() {
        Old unknown = new Old(List.of(1L, 3L), Arrays.asList(100 * MIN, null), resume(MIN), null);
        Old zero = new Old(List.of(1L, 3L), List.of(100 * MIN, 0L), resume(MIN), null);

        assertThat(StateCarry.split(List.of(unknown, zero), Map.of(1L, 8L, 3L, 8L))).isEmpty();
        assertThat(StateCarry.land(List.of(), List.of(1L, 3L), TIMES)).isNull();
    }

    @Test
    void nothingToLandGivesNoStateAndNoMarker() {
        assertThat(StateCarry.land(List.of(), List.of(1L), TIMES)).isEqualTo(new Result(null, null));
    }

    @Test
    void filesAreInPlayOrderByPartThenId() {
        assertThat(StateCarry.inPlayOrder(Map.of(5L, 2, 6L, 1, 7L, 1), List.of(5L, 7L, 6L)))
            .containsExactly(6L, 7L, 5L);
    }
}
