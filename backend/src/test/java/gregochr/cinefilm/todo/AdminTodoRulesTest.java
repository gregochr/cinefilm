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
        assertThat(AdminTodoRules.confident(List.of(60, 48))).isFalse();
        assertThat(AdminTodoRules.confident(List.of(61, 48))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(61, 10))).isTrue();
    }

    @Test
    void theLeadMustExceedTheMarginByOneBecauseStoredScoresAreFloored() {
        // Raw 75.2 and 65.4 (a real lead of 9.8, which the matcher refuses) are stored as 75 and 65.
        assertThat(AdminTodoRules.confident(List.of(75, 65))).isFalse();
        // Raw 70.0 and 60.0 would be accepted, but floored whole numbers cannot prove it: the safe side.
        assertThat(AdminTodoRules.confident(List.of(70, 60))).isFalse();
        assertThat(AdminTodoRules.confident(List.of(71, 60))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(80, 70))).isFalse();
        assertThat(AdminTodoRules.confident(List.of(80, 69))).isTrue();
    }

    @Test
    void twoTiedCandidatesAreNotConfident() {
        assertThat(AdminTodoRules.confident(List.of(90, 90))).isFalse();
    }

    @Test
    void aSingleCandidateMustBeAboveTheAcceptScoreBecauseOldRowsWereRounded() {
        assertThat(AdminTodoRules.confident(List.of(59))).isFalse();
        // A stored 60 may be a rounded 59.5, which the matcher refuses: not confident (the safe side).
        assertThat(AdminTodoRules.confident(List.of(60))).isFalse();
        assertThat(AdminTodoRules.confident(List.of(61))).isTrue();
        // A tagged match the guard refused is stored at REFUSED_SCORE, 100.
        assertThat(AdminTodoRules.confident(List.of(100))).isTrue();
    }

    @Test
    void theOrderOfTheScoresDoesNotMatter() {
        assertThat(AdminTodoRules.confident(List.of(50, 80, 65))).isTrue();
        assertThat(AdminTodoRules.confident(List.of(71, 80, 65))).isFalse();
    }

    @Test
    void aStrongRunnerUpWithAWeakTopIsNotConfident() {
        assertThat(AdminTodoRules.confident(List.of(55, 40))).isFalse();
    }

    // ---- full subtitles ----

    /** Needed, nothing playing, an IMDb id, searched, and the scheduler would download something: being checked. */
    private static FullFacts full(boolean needed, boolean plays, boolean imdb, boolean searched, boolean due) {
        return new FullFacts(needed, false, plays, imdb, searched, due, true, true);
    }

    @Test
    void aFullRowWithNoImdbIdIsUnresolvedWhateverElse() {
        FullFacts facts = full(true, false, false, true, true);

        assertThat(AdminTodoRules.unresolvedFull(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
        FullFacts notSearched = full(true, false, false, false, false);
        assertThat(AdminTodoRules.unresolvedFull(notSearched)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(notSearched)).isFalse();
    }

    @Test
    void aFullRowNotYetSearchedForItsCurrentImdbIdIsBeingChecked() {
        FullFacts facts = full(true, false, true, false, false);

        assertThat(AdminTodoRules.beingCheckedFull(facts)).isTrue();
        assertThat(AdminTodoRules.unresolvedFull(facts)).isFalse();
    }

    @Test
    void aFullRowSearchedWhereTheSchedulerWouldDownloadNothingIsUnresolved() {
        // No candidates, every candidate blocked or already compared, or every edition at the cap.
        FullFacts facts = full(true, false, true, true, false);

        assertThat(AdminTodoRules.unresolvedFull(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
    }

    @Test
    void aFullRowSearchedWhereTheSchedulerWouldDownloadSomethingIsBeingChecked() {
        FullFacts facts = full(true, false, true, true, true);

        assertThat(AdminTodoRules.beingCheckedFull(facts)).isTrue();
        assertThat(AdminTodoRules.unresolvedFull(facts)).isFalse();
    }

    @Test
    void aFullRowThatPlaysASubtitleIsNeitherWhateverTheRest() {
        for (boolean searched : new boolean[] {true, false}) {
            FullFacts facts = full(true, true, true, searched, true);

            assertThat(AdminTodoRules.inPlayFull(facts)).isFalse();
            assertThat(AdminTodoRules.unresolvedFull(facts)).isFalse();
            assertThat(AdminTodoRules.beingCheckedFull(facts)).isFalse();
        }
    }

    @Test
    void aFullRowIsSubtitledOnlyWhenNeededAndAFullSubtitlePlays() {
        assertThat(AdminTodoRules.subtitledFull(full(true, true, false, false, false))).isTrue();
        assertThat(AdminTodoRules.subtitledFull(full(true, false, true, false, true))).isFalse();
        assertThat(AdminTodoRules.subtitledFull(full(false, true, true, true, true))).isFalse();
        assertThat(AdminTodoRules.subtitledFull(full(false, false, false, false, false))).isFalse();
    }

    @Test
    void aFullRowThatIsNotNeededIsNeitherWhateverTheRest() {
        // UNKNOWN, UNSURE and NOT_NEEDED all arrive as needed = false.
        FullFacts facts = full(false, false, false, false, false);
        FullFacts withEverything = full(false, false, true, false, true);

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
                        for (boolean due : both) {
                            FullFacts f = full(needed, plays, imdb, searched, due);
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

    // ---- forced subtitles ----

    private static ForcedFacts forced(boolean needed, boolean has, boolean imdb, Integer count, boolean due) {
        return new ForcedFacts(needed, has, imdb, count, false, due, true, true);
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

    // ---- candidates missing ----

    @Test
    void aSearchedForcedRowWithNoStoredCandidatesIsBeingCheckedWhileSearchIsOn() {
        // The search scheduler picks every film with an IMDb id and no stored candidates, whatever the count.
        for (Integer count : new Integer[] {0, 3}) {
            ForcedFacts on = new ForcedFacts(true, false, true, count, true, false, true, true);
            ForcedFacts searchOff = new ForcedFacts(true, false, true, count, true, false, false, true);

            assertThat(AdminTodoRules.beingCheckedForced(on)).isTrue();
            assertThat(AdminTodoRules.unresolvedForced(on)).isFalse();
            assertThat(AdminTodoRules.beingCheckedForced(searchOff)).isFalse();
            assertThat(AdminTodoRules.unresolvedForced(searchOff)).isTrue();
        }
    }

    @Test
    void aFilmWithNoImdbIdIsUnresolvedEvenWithCandidatesMissing() {
        ForcedFacts facts = new ForcedFacts(true, false, false, 3, true, true, true, true);

        assertThat(AdminTodoRules.unresolvedForced(facts)).isTrue();
        assertThat(AdminTodoRules.beingCheckedForced(facts)).isFalse();
    }

    @Test
    void storedCandidatesLeaveASearchedForcedRowToTheDownloadRule() {
        ForcedFacts nothingDue = new ForcedFacts(true, false, true, 3, false, false, true, true);

        assertThat(AdminTodoRules.beingCheckedForced(nothingDue)).isFalse();
        assertThat(AdminTodoRules.unresolvedForced(nothingDue)).isTrue();
    }

    // ---- OpenSubtitles off ----

    @Test
    void aFullRowNotSearchedIsBeingCheckedOnlyWhileSearchingIsOn() {
        FullFacts on = new FullFacts(true, false, false, true, false, false, true, true);
        FullFacts searchOff = new FullFacts(true, false, false, true, false, false, false, true);

        assertThat(AdminTodoRules.beingCheckedFull(on)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(searchOff)).isFalse();
        assertThat(AdminTodoRules.unresolvedFull(searchOff)).isTrue();
    }

    @Test
    void aFullDownloadDueCountsOnlyWhileDownloadsAreOn() {
        FullFacts on = new FullFacts(true, false, false, true, true, true, true, true);
        FullFacts downloadsOff = new FullFacts(true, false, false, true, true, true, true, false);

        assertThat(AdminTodoRules.beingCheckedFull(on)).isTrue();
        assertThat(AdminTodoRules.beingCheckedFull(downloadsOff)).isFalse();
        assertThat(AdminTodoRules.unresolvedFull(downloadsOff)).isTrue();
    }

    @Test
    void aFullRowNotSearchedStaysBeingCheckedWithDownloadsOffButSearchOn() {
        assertThat(AdminTodoRules.beingCheckedFull(new FullFacts(true, false, false, true, false, false, true, false)))
            .isTrue();
    }

    @Test
    void aForcedRowNotSearchedIsBeingCheckedOnlyWhileSearchingIsOn() {
        ForcedFacts on = new ForcedFacts(true, false, true, null, false, false, true, true);
        ForcedFacts searchOff = new ForcedFacts(true, false, true, null, false, false, false, true);

        assertThat(AdminTodoRules.beingCheckedForced(on)).isTrue();
        assertThat(AdminTodoRules.beingCheckedForced(searchOff)).isFalse();
        assertThat(AdminTodoRules.unresolvedForced(searchOff)).isTrue();
    }

    @Test
    void aForcedDownloadDueCountsOnlyWhileDownloadsAreOn() {
        ForcedFacts on = new ForcedFacts(true, false, true, 3, false, true, true, true);
        ForcedFacts downloadsOff = new ForcedFacts(true, false, true, 3, false, true, true, false);

        assertThat(AdminTodoRules.beingCheckedForced(on)).isTrue();
        assertThat(AdminTodoRules.beingCheckedForced(downloadsOff)).isFalse();
        assertThat(AdminTodoRules.unresolvedForced(downloadsOff)).isTrue();
    }

    @Test
    void aNoneExistFilmIsNeitherInPlayNorUnresolvedNorBeingChecked() {
        FullFacts none = new FullFacts(true, true, false, true, true, true, true, true);
        assertThat(AdminTodoRules.inPlayFull(none)).isFalse();
        assertThat(AdminTodoRules.beingCheckedFull(none)).isFalse();
        assertThat(AdminTodoRules.unresolvedFull(none)).isFalse();
        assertThat(AdminTodoRules.subtitledFull(none)).isFalse();
    }

    @Test
    void aNoneExistFilmWithASubtitlePlayingIsSubtitled() {
        FullFacts played = new FullFacts(true, true, true, true, true, false, true, true);
        assertThat(AdminTodoRules.subtitledFull(played)).isTrue();
        assertThat(AdminTodoRules.unresolvedFull(played)).isFalse();
    }

    @Test
    void everyFullCombinationMatchesTheDocumentedTruthTable() {
        boolean[] both = {true, false};
        for (boolean needed : both) {
            for (boolean plays : both) {
                for (boolean imdb : both) {
                    for (boolean searched : both) {
                        for (boolean due : both) {
                            for (boolean search : both) {
                                for (boolean downloads : both) {
                                    FullFacts f = new FullFacts(needed, false, plays, imdb, searched, due, search,
                                        downloads);
                                    // The documented rule, written out: in play when needed with nothing playing;
                                    // being checked when it has an IMDb id and either the search is still to come
                                    // (and on) or a download is due (and on); unresolved is the rest of in play.
                                    boolean inPlay = needed && !plays;
                                    boolean checked = inPlay && imdb && (!searched && search || due && downloads);
                                    assertThat(AdminTodoRules.beingCheckedFull(f)).isEqualTo(checked);
                                    assertThat(AdminTodoRules.unresolvedFull(f)).isEqualTo(inPlay && !checked);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyForcedCombinationMatchesTheDocumentedTruthTable() {
        boolean[] both = {true, false};
        for (boolean needed : both) {
            for (boolean has : both) {
                for (boolean imdb : both) {
                    for (Integer count : new Integer[] {null, 0, 2}) {
                        for (boolean missing : both) {
                            for (boolean due : both) {
                                for (boolean search : both) {
                                    for (boolean downloads : both) {
                                        ForcedFacts g = new ForcedFacts(needed, has, imdb, count, missing, due,
                                            search, downloads);
                                        // In play when needed with no subtitle served. Being checked when it has an
                                        // IMDb id and either the search is still to come or its candidates are
                                        // missing (needs search on), or files were found and a download is due
                                        // (needs downloads on).
                                        boolean inPlay = needed && !has;
                                        boolean checked = inPlay && imdb
                                            && (missing || count == null ? search : count > 0 && due && downloads);
                                        assertThat(AdminTodoRules.beingCheckedForced(g)).isEqualTo(checked);
                                        assertThat(AdminTodoRules.unresolvedForced(g)).isEqualTo(inPlay && !checked);
                                        if (!inPlay) {
                                            assertThat(AdminTodoRules.beingCheckedForced(g)
                                                || AdminTodoRules.unresolvedForced(g)).isFalse();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
