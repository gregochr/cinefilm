package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.library.StateCarry.Old;
import gregochr.cinefilm.library.StateCarry.Piece;
import gregochr.cinefilm.library.StateCarry.Result;
import gregochr.cinefilm.library.StateFolding.State;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link StateCarry}: state and credits markers following the files, with the {@link StateFolding} rules. */
class StateCarryTest {

    private static State resume(long positionMs) {
        return new State(positionMs, false, null, NOW, false, null);
    }

    private static final Map<Long, Long> TIMES = Map.of(1L, 100 * MIN, 2L, 50 * MIN, 3L, 0L);

    @Test
    void oneEditionWhoseFilesAllStayHandsEverythingToTheSameEdition() {
        Old old = new Old(List.of(1L, 2L), List.of(100 * MIN, 50 * MIN), resume(120 * MIN), 140 * MIN);

        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(old), Map.of(1L, 9L, 2L, 9L));

        assertThat(pieces).containsOnlyKeys(9L);
        assertThat(pieces.get(9L)).containsExactly(new Piece(resume(120 * MIN), 140 * MIN, List.of(1L, 2L)));
        assertThat(StateCarry.land(pieces.get(9L), List.of(1L, 2L), TIMES))
            .isEqualTo(new Result(resume(120 * MIN), 140 * MIN));
    }

    @Test
    void anEditionSplitInTwoHandsEachNewEditionTheShareThatIsInItsFiles() {
        Old old = new Old(List.of(1L, 2L), List.of(100 * MIN, 50 * MIN), resume(120 * MIN), 140 * MIN);

        Map<Long, List<Piece>> pieces = StateCarry.split(List.of(old), Map.of(1L, 8L, 2L, 9L));

        assertThat(pieces).containsOnlyKeys(9L);
        assertThat(pieces.get(9L)).containsExactly(new Piece(resume(20 * MIN), 40 * MIN, List.of(2L)));
    }

    @Test
    void aFileLeavingTheFilmTakesItsShareWithIt() {
        Old old = new Old(List.of(1L, 2L), List.of(100 * MIN, 50 * MIN), resume(120 * MIN), 140 * MIN);
        java.util.HashMap<Long, Long> targets = new java.util.HashMap<>();
        targets.put(1L, 8L);
        targets.put(2L, null);

        assertThat(StateCarry.split(List.of(old), targets)).isEmpty();
    }

    @Test
    void aGroupWithNothingToCarryAddsNoPiece() {
        Old old = new Old(List.of(1L, 2L), List.of(100 * MIN, 50 * MIN), null, null);
        Old marked = new Old(List.of(1L), List.of(100 * MIN), null, 30 * MIN);

        assertThat(StateCarry.split(List.of(old), Map.of(1L, 8L, 2L, 9L))).isEmpty();
        assertThat(StateCarry.split(List.of(marked), Map.of(1L, 8L)).get(8L))
            .containsExactly(new Piece(null, 30 * MIN, List.of(1L)));
    }

    @Test
    void anEditionWithAFileOfUnknownDurationIsNotCarried() {
        Old unknown = new Old(List.of(1L, 3L), java.util.Arrays.asList(100 * MIN, null), resume(MIN), null);
        Old zero = new Old(List.of(1L, 3L), List.of(100 * MIN, 0L), resume(MIN), null);

        assertThat(StateCarry.split(List.of(unknown, zero), Map.of(1L, 8L, 3L, 8L))).isEmpty();
        assertThat(StateCarry.land(List.of(), List.of(1L, 3L), TIMES)).isNull();
    }

    @Test
    void piecesFromSeveralEditionsFoldOntoTheNewTimeline() {
        Piece first = new Piece(resume(0), null, List.of(1L));
        Piece second = new Piece(resume(5 * MIN), 40 * MIN, List.of(2L));

        Result result = StateCarry.land(List.of(first, second), List.of(1L, 2L), TIMES);

        assertThat(result.state().positionMs()).isEqualTo(105 * MIN);
        assertThat(result.markerMs()).as("part 2's marker plus part 1's length").isEqualTo(140 * MIN);
    }

    @Test
    void aMarkerOnAPartThatIsNotTheLastIsDropped() {
        Piece first = new Piece(null, 30 * MIN, List.of(1L));

        Result result = StateCarry.land(List.of(first), List.of(1L, 2L), TIMES);

        assertThat(result).isEqualTo(new Result(null, null));
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
