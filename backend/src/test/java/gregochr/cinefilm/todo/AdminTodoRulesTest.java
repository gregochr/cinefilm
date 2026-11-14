package gregochr.cinefilm.todo;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.tmdb.TmdbMatcher;
import gregochr.cinefilm.todo.AdminTodoRules.ForcedFacts;
import gregochr.cinefilm.todo.AdminTodoRules.FullFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The shared To do rules, branch by branch and at every boundary. */
class AdminTodoRulesTest {

    // ---- the confident match ----

    @Test
    void theRuleUsesTheMatchersOwnConstants() {
        assertThat(TmdbMatcher.ACCEPT_SCORE).isEqualTo(60);
        assertThat(TmdbMatcher.MARGIN).isEqualTo(10);
    }

    @Test
    void noCandidatesIsNotConfident() {
        assertThat(AdminTodoRules.confident(List.of())).isFalse();
        assertThat(AdminTodoRules.confident(null)).isFalse();
    }

    @Test
    void theTopScoreMustReachTheAcceptScore() {
        assertThat(AdminTodoRules.confident(List.of(59, 10))).isFalse();
        assertThat(AdminTodoRules.confident(List.of(60, 50))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(61, 10))).isTrue();
    }

    @Test
    void theLeadOverTheSecondMustReachTheMargin() {
        assertThat(AdminTodoRules.confident(List.of(80, 71))).isFalse();
        assertThat(AdminTodoRules.confident(List.of(80, 70))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(80, 69))).isTrue();
    }

    @Test
    void twoTiedCandidatesAreNotConfident() {
        assertThat(AdminTodoRules.confident(List.of(90, 90))).isFalse();
    }

    @Test
    void aSingleCandidateIsConfidentAtTheAcceptScoreNotBelow() {
        // The guard-refused case: LibraryIngest stores one candidate at REFUSED_SCORE = 60.
        assertThat(AdminTodoRules.confident(List.of(60))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(59))).isFalse();
    }

    @Test
    void theOrderOfTheScoresDoesNotMatter() {
        assertThat(AdminTodoRules.confident(List.of(50, 80, 65))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(75, 80, 65))).isFalse();
    }

    @Test
    void aStrongRunnerUpWithAWeakTopIsNotConfident() {
        assertThat(AdminTodoRules.confident(List.of(55, 40))).isFalse();
    }

    // ---- full subtitles ----

    /** Needed, nothing playing, an IMDb id, searched, a candidate and a due edition: being checked. */
    private static FullFacts full(boolean needed, boolean plays, boolean imdb, boolean searched, boolean candidate,
                                  boolean due) {
        return new FullFacts(needed, plays, imdb, searched, candidate, due);
    }

    @Test
    void aFullRowWithNoImdbIdIsUnresolvedWhateverElse() {
        FullFacts facts = full(true, false, false, true, true, true);

        assertThat(AdminTodoRules.unresolvedFull(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
        FullFacts notSearched = full(true, false, false, false, false, false);
        assertThat(AdminTodoRules.unresolvedFull(notSearched)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(notSearched)).isFalse();
    }

    @Test
    void aFullRowNotYetSearchedForItsCurrentImdbIdIsBeingChecked() {
        FullFacts facts = full(true, false, true, false, false, false);

        assertThat(AdminTodoRules.beingCheckedFull(facts)).isTrue();
        assertThat(AdminTodoRules.unresolvedFull(facts)).isFalse();
    }

    @Test
    void aFullRowSearchedWithNoCandidatesIsUnresolved() {
        FullFacts facts = full(true, false, true, true, false, true);

        assertThat(AdminTodoRules.unresolvedFull(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
    }

    @Test
    void aFullRowWithCandidatesButEveryEditionAtTheCapIsUnresolved() {
        FullFacts facts = full(true, false, true, true, true, false);

        assertThat(AdminTodoRules.unresolvedFull(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
    }

    @Test
    void aFullRowWithCandidatesAndAnEditionUnderTheCapIsBeingChecked() {
        FullFacts facts = full(true, false, true, true, true, true);

        assertThat(AdminTodoRules.beingCheckedFull(facts)).isTrue();
        assertThat(AdminTodoRules.unresolvedFull(facts)).isFalse();
    }

    @Test
    void aFullRowThatPlaysASubtitleIsNeitherWhateverTheRest() {
        for (boolean searched : new boolean[] {true, false}) {
            FullFacts facts = full(true, true, true, searched, true, true);

            assertThat(AdminTodoRules.inPlayFull(facts)).isFalse();
            assertThat(AdminTodoRules.unresolvedFull(facts)).isFalse();
            assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
        }
    }

    @Test
    void aFullRowThatIsNotNeededIsNeitherWhateverTheRest() {
        // UNKNOWN, UNSURE and NOT_NEEDED all arrive as needed = false.
        FullFacts facts = full(false, false, false, false, false, false);
        FullFacts withEverything = full(false, false, true, false, true, true);

        for (FullFacts f : List.of(facts, withEverything)) {
            assertThat(AdminTodoRules.inPlayFull(f)).isFalse();
            assertThat(AdminTodoRules.unresolvedFull(f)).isFalse();
            assertThat(AdminTodoRules.beingCheckedFull(f)).isFalse();
        }
    }

    @Test
    void everyFullRowInPlayIsExactlyOneOfUnresolvedAndBeingChecked() {
        boolean[] both = {true, false};
        for (boolean needed : both) {
            for (boolean plays : both) {
                for (boolean imdb : both) {
                    for (boolean searched : both) {
                        for (boolean candidate : both) {
                            for (boolean due : both) {
                                FullFacts f = full(needed, plays, imdb, searched, candidate, due);
                                boolean inPlay = needed && !plays;
                                assertThat(AdminTodoRules.inPlayFull(f)).isEqualTo(inPlay);
                                assertThat(AdminTodoRules.unresolvedFull(f) || AdminTodoRules.beingCheckedFull(f))
                                    .isEqualTo(inPlay);
                                assertThat(AdminTodoRules.unresolvedFull(f) && AdminTodoRules.beingCheckedFull(f))
                                    .isFalse();
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- forced subtitles ----

    private static ForcedFacts forced(boolean needed, boolean has, boolean imdb, Integer count, boolean due) {
        return new ForcedFacts(needed, has, imdb, count, due);
    }

    @Test
    void aForcedRowWithNoImdbIdIsUnresolved() {
        ForcedFacts facts = forced(true, false, false, null, true);

        assertThat(AdminTodoRules.unresolvedForced(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedForced(facts)).isFalse();
    }

    @Test
    void aForcedRowNotSearchedForItsCurrentImdbIdIsBeingChecked() {
        ForcedFacts facts = forced(true, false, true, null, false);

        assertThat(AdminTodoRules.beingCheckedForced(facts)).isTrue();
        assertThat(AdminTodoRules.unresolvedForced(facts)).isFalse();
    }

    @Test
    void aForcedRowSearchedWithNoFilesIsUnresolvedEvenWithADueEdition() {
        ForcedFacts facts = forced(true, false, true, 0, true);

        assertThat(AdminTodoRules.unresolvedForced(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedForced(facts)).isFalse();
    }

    @Test
    void aForcedRowWithFilesButNoDueEditionWithRoomIsUnresolved() {
        ForcedFacts facts = forced(true, false, true, 3, false);

        assertThat(AdminTodoRules.unresolvedForced(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedForced(facts)).isFalse();
    }

    @Test
    void aForcedRowWithFilesAndADueEditionWithRoomIsBeingChecked() {
        ForcedFacts one = forced(true, false, true, 1, true);

        assertThat(AdminTodoRules.beingCheckedForced(one)).isTrue();
        assertThat(AdminTodoRules.unresolvedForced(one)).isFalse();
    }

    @Test
    void aForcedRowThatHasASubtitleOrIsNotNeededIsNeither() {
        for (ForcedFacts facts : List.of(forced(true, true, true, null, true), forced(true, true, true, 3, true),
            forced(false, false, true, null, true), forced(false, false, false, 3, true))) {
            assertThat(AdminTodoRules.inPlayForced(facts)).isFalse();
            assertThat(AdminTodoRules.unresolvedForced(facts)).isFalse();
            assertThat(AdminTodoRules.beingCheckedForced(facts)).isFalse();
        }
    }

    @Test
    void everyForcedRowInPlayIsExactlyOneOfUnresolvedAndBeingChecked() {
        boolean[] both = {true, false};
        for (boolean needed : both) {
            for (boolean has : both) {
                for (boolean imdb : both) {
                    for (Integer count : new Integer[] {null, 0, 4}) {
                        for (boolean due : both) {
                            ForcedFacts f = forced(needed, has, imdb, count, due);
                            // Today's needed-missing filter (frontend state/forcedNeed.js).
                            boolean neededMissing = needed && !has;
                            assertThat(AdminTodoRules.inPlayForced(f)).isEqualTo(neededMissing);
                            assertThat(AdminTodoRules.unresolvedForced(f) || AdminTodoRules.beingCheckedForced(f))
                                .isEqualTo(neededMissing);
                            assertThat(AdminTodoRules.unresolvedForced(f) && AdminTodoRules.beingCheckedForced(f))
                                .isFalse();
                        }
                    }
                }
            }
        }
    }
}
