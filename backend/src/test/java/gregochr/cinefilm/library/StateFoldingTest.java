package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.library.StateFolding.Located;
import gregochr.cinefilm.library.StateFolding.Source;
import gregochr.cinefilm.library.StateFolding.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Folding the watch state of editions that join, and unfolding the state of one that splits. */
class StateFoldingTest {

    private static final long MIN = 60_000L;
    private static final Instant MONDAY = Instant.parse("2024-03-04T20:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2024-03-05T20:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2024-03-06T20:00:00Z");
    private static final long TOTAL = 200 * MIN;

    private static State state(long position, boolean watched, Instant lastPlayed) {
        return new State(position, watched, watched ? lastPlayed : null, lastPlayed, false, null);
    }

    /** Part 1 covers 0-90 minutes, part 2 covers 90-200. */
    private static Source first(State state) {
        return new Source(0, 90 * MIN, false, state);
    }

    private static Source second(State state) {
        return new Source(90 * MIN, TOTAL, true, state);
    }

    @Test
    void noStateAnywhereFoldsToNothing() {
        assertThat(StateFolding.fold(List.of(first(null), second(null)), TOTAL)).isNull();
    }

    @Test
    void aSecondPartsResumeBecomesAGlobalPosition() {
        State folded = StateFolding.fold(List.of(first(null), second(state(25 * MIN, false, MONDAY))), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(115 * MIN);
        assertThat(folded.watched()).isFalse();
        assertThat(folded.lastPlayedAt()).isEqualTo(MONDAY);
    }

    @Test
    void aFirstPartsResumeStaysWhereItWas() {
        State folded = StateFolding.fold(List.of(first(state(30 * MIN, false, MONDAY)), second(null)), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(30 * MIN);
    }

    @Test
    void aWatchedFirstPartAndAnUnplayedSecondIsNotWatchedAndResumesAtTheStartOfTheSecond() {
        State folded = StateFolding.fold(List.of(first(state(0, true, MONDAY)), second(null)), TOTAL);

        assertThat(folded.watched()).isFalse();
        assertThat(folded.watchedAt()).isNull();
        assertThat(folded.positionMs()).isEqualTo(90 * MIN);
    }

    @Test
    void aWatchedFirstPartAndAStartedSecondResumesInsideTheSecond() {
        State folded = StateFolding.fold(List.of(first(state(0, true, MONDAY)),
            second(state(10 * MIN, false, TUESDAY))), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(100 * MIN);
        assertThat(folded.watched()).isFalse();
        assertThat(folded.lastPlayedAt()).isEqualTo(TUESDAY);
    }

    @Test
    void theEditionHoldingTheLastPartBeingWatchedMakesTheWholeWatched() {
        State folded = StateFolding.fold(List.of(first(state(30 * MIN, false, MONDAY)),
            new Source(90 * MIN, TOTAL, true, new State(0, true, TUESDAY, TUESDAY, false, null))), TOTAL);

        assertThat(folded.watched()).isTrue();
        assertThat(folded.watchedAt()).isEqualTo(TUESDAY);
        assertThat(folded.positionMs()).isZero();
    }

    @Test
    void theStateThatWasPlayedLastDecidesThePositionAndTheHiddenFlag() {
        State older = new State(30 * MIN, false, null, MONDAY, true, MONDAY);
        State newer = new State(5 * MIN, false, null, WEDNESDAY, false, null);

        State folded = StateFolding.fold(List.of(first(older), second(newer)), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(95 * MIN);
        assertThat(folded.hidden()).isFalse();
        assertThat(folded.lastPlayedAt()).isEqualTo(WEDNESDAY);
    }

    @Test
    void whenTheLatestPlayedStateHasNoProgressTheFurthestOtherProgressIsUsed() {
        State idle = new State(0, false, null, WEDNESDAY, false, null);

        State folded = StateFolding.fold(List.of(first(state(40 * MIN, false, MONDAY)), second(idle)), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(40 * MIN);
    }

    @Test
    void aStateWithNoPlayTimeLosesToOneWithAPlayTime() {
        State untimed = new State(10 * MIN, false, null, null, false, null);

        State folded = StateFolding.fold(List.of(first(untimed), second(state(20 * MIN, false, MONDAY))), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(110 * MIN);
    }

    @Test
    void anOlderStateAfterANewerOneDoesNotReplaceIt() {
        State folded = StateFolding.fold(List.of(first(state(40 * MIN, false, WEDNESDAY)),
            second(state(20 * MIN, false, MONDAY))), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(40 * MIN);
        assertThat(folded.lastPlayedAt()).isEqualTo(WEDNESDAY);
    }

    @Test
    void twoStatesWithNoPlayTimeKeepTheFirst() {
        State one = new State(10 * MIN, false, null, null, false, null);
        State two = new State(20 * MIN, false, null, null, false, null);

        State folded = StateFolding.fold(List.of(first(one), second(two)), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(10 * MIN);
        assertThat(folded.lastPlayedAt()).isNull();
    }

    @Test
    void anEditionThatNeverHadAStateIsSkippedWhenLookingForProgress() {
        State idle = new State(0, false, null, WEDNESDAY, false, null);

        State folded = StateFolding.fold(List.of(first(null), second(idle)), TOTAL);

        assertThat(folded.positionMs()).isZero();
        assertThat(folded.watched()).isFalse();
    }

    @Test
    void thePositionNeverPassesTheJoinedRuntime() {
        State folded = StateFolding.fold(List.of(first(null), second(state(500 * MIN, false, MONDAY))), TOTAL);

        assertThat(folded.positionMs()).isEqualTo(TOTAL);
    }

    @Test
    void aSurvivorThatAlreadyHoldsEveryPartKeepsItsWatchedFlag() {
        State folded = StateFolding.fold(List.of(new Source(0, TOTAL, true, state(0, true, MONDAY))), TOTAL);

        assertThat(folded.watched()).isTrue();
    }

    @Test
    void unfoldingGivesTheResumeToThePartHoldingIt() {
        List<State> unfolded = StateFolding.unfold(state(100 * MIN, false, MONDAY), List.of(90 * MIN, 110 * MIN),
            List.of(List.of(0), List.of(1)));

        assertThat(unfolded.get(0)).isNull();
        assertThat(unfolded.get(1).positionMs()).isEqualTo(10 * MIN);
        assertThat(unfolded.get(1).lastPlayedAt()).isEqualTo(MONDAY);
    }

    @Test
    void unfoldingCountsOnlyTheEarlierPartsOfTheSameGroup() {
        List<State> unfolded = StateFolding.unfold(state(250 * MIN, false, MONDAY),
            List.of(90 * MIN, 60 * MIN, 110 * MIN), List.of(List.of(0, 2), List.of(1)));

        assertThat(unfolded.get(0).positionMs()).isEqualTo(190 * MIN);
        assertThat(unfolded.get(1)).isNull();
    }

    @Test
    void unfoldingAWatchedEditionWatchesTheGroupHoldingTheLastPart() {
        State watched = new State(0, true, TUESDAY, WEDNESDAY, true, MONDAY);

        List<State> unfolded = StateFolding.unfold(watched, List.of(90 * MIN, 110 * MIN),
            List.of(List.of(1), List.of(0)));

        assertThat(unfolded.get(0)).isEqualTo(new State(0, true, TUESDAY, WEDNESDAY, true, MONDAY));
        assertThat(unfolded.get(1)).isNull();
    }

    @Test
    void unfoldingNothingGivesNothing() {
        assertThat(StateFolding.unfold(null, List.of(90 * MIN, 110 * MIN), List.of(List.of(0), List.of(1))))
            .containsExactly(null, null);
    }

    @Test
    void relocateFindsTheGroupAndTheTimeInIt() {
        assertThat(StateFolding.relocate(95 * MIN, List.of(90 * MIN, 110 * MIN), List.of(List.of(0), List.of(1))))
            .isEqualTo(new Located(1, 5 * MIN));
    }

    @Test
    void aPartInNoGroupIsRejected() {
        assertThatThrownBy(() -> StateFolding.relocate(95 * MIN, List.of(90 * MIN, 110 * MIN), List.of(List.of(0))))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Part 1 is in no group");
    }
}
