package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.subtitle.ReferenceWalk.Attempt;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When the walk over an edition's OpenSubtitles candidates moves on, and which result it keeps. */
class ReferenceWalkTest {

    private static Result check(int served, int reference, int matched, int offsetMs, boolean full) {
        return new Result(served, reference, served == 0 ? 0.0 : (double) matched / served, offsetMs, Verdict.CHECK,
            List.of("a reason"), matched, full);
    }

    private static Result agrees(int served, int reference, int matched, boolean full) {
        return new Result(served, reference, 1.0, 0, Verdict.AGREES, List.of("fine"), matched, full);
    }

    private static Attempt attempt(long id, Result result) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(id);
        return new Attempt(reference, result);
    }

    @Test
    void anAgreementNeverMovesOnNotEvenAFullReferenceOrAFarOffset() {
        assertThat(ReferenceWalk.moveOn(agrees(12, 41, 12, false))).isFalse();
        assertThat(ReferenceWalk.moveOn(agrees(39, 1362, 39, true))).isFalse();
        assertThat(ReferenceWalk.moveOn(new Result(12, 12, 1.0, 114_000, Verdict.AGREES, List.of(), 12, false)))
            .isFalse();
    }

    @Test
    void aCheckAgainstAFullReferenceMovesOn() {
        assertThat(ReferenceWalk.moveOn(check(39, 1362, 20, 0, true))).isTrue();
        assertThat(ReferenceWalk.moveOn(check(0, 300, 0, 0, true))).isTrue();
    }

    @Test
    void theOffsetBoundaryIsThirtySecondsExactlyStaysAndOneStepMoreMovesOn() {
        assertThat(ReferenceWalk.looksLikeOtherCut(check(12, 41, 12, 30_000, false))).isFalse();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(12, 41, 12, 30_100, false))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(12, 41, 12, -30_000, false))).isFalse();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(12, 41, 12, -30_100, false))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(53, 12, 3, -114_000, false))).isTrue();
        assertThat(ReferenceWalk.OTHER_CUT_OFFSET_MS).isEqualTo(30_000);
    }

    @Test
    void theShareBoundaryIsThirtyPercentExactlyStaysAndOneLineFewerMovesOn() {
        // 10 served lines: 3 matched is 30%, 2 matched is 20%.
        assertThat(ReferenceWalk.looksLikeOtherCut(check(10, 10, 3, 0, false))).isFalse();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(10, 10, 2, 0, false))).isTrue();
        // 43 served lines: 12 matched is 27.9% (under), 13 is 30.2% (over): whole-number arithmetic.
        assertThat(ReferenceWalk.looksLikeOtherCut(check(43, 50, 12, 0, false))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(43, 50, 13, 0, false))).isFalse();
        assertThat(ReferenceWalk.OTHER_CUT_PERCENT).isEqualTo(30);
    }

    @Test
    void theShareTestNeedsEightLinesInBothFiles() {
        assertThat(ReferenceWalk.looksLikeOtherCut(check(8, 8, 0, 0, false))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(7, 8, 0, 0, false))).isFalse();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(8, 7, 0, 0, false))).isFalse();
        // Rings of the Kings in production: 12 lines against 3, nothing lines up.
        assertThat(ReferenceWalk.looksLikeOtherCut(check(12, 3, 0, 0, false))).isFalse();
        assertThat(ReferenceWalk.OTHER_CUT_MIN_LINES).isEqualTo(8);
    }

    @Test
    void anIncompleteFileThatLinesUpIsNotAnotherCut() {
        assertThat(ReferenceWalk.looksLikeOtherCut(check(12, 41, 12, 0, false))).isFalse();
        assertThat(ReferenceWalk.moveOn(check(12, 41, 12, 0, false))).isFalse();
    }

    @Test
    void aServedFileThatIsItselfFullSubtitlesNeverMovesOnBecauseAnotherCutWouldNotChangeThat() {
        // Amsterdam in production: 2,431 lines against 43.
        assertThat(ReferenceWalk.moveOn(check(2431, 43, 20, 0, false))).isFalse();
        // The same shape one line short of 300 is judged like any other file.
        assertThat(ReferenceWalk.looksLikeOtherCut(check(299, 20, 3, 0, false))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(300, 20, 3, 0, false))).isFalse();
        // At exactly twice the reference it is not "more than twice", so the share test still applies.
        assertThat(ReferenceWalk.looksLikeOtherCut(check(300, 150, 3, 0, false))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(300, 149, 3, 0, false))).isFalse();
    }

    @Test
    void onlyACheckCanLookLikeAnotherCutAndAFullReferenceIsNotJudgedByTheServedFilesLength() {
        assertThat(ReferenceWalk.looksLikeOtherCut(agrees(12, 41, 12, false))).isFalse();
        // Judged by the offset and share like any other, whatever the served length.
        assertThat(ReferenceWalk.looksLikeOtherCut(check(400, 300, 3, 0, true))).isTrue();
        assertThat(ReferenceWalk.looksLikeOtherCut(check(400, 300, 300, 0, true))).isFalse();
    }

    @Test
    void theCapOfCandidatesIsThree() {
        assertThat(ReferenceWalk.MAX_CANDIDATES).isEqualTo(3);
    }

    @Test
    void theFirstAgreementIsKeptEvenWhenAnEarlierOrLaterCheckLinedUpBetter() {
        Attempt first = attempt(1, check(39, 50, 30, 0, false));
        Attempt agreed = attempt(2, agrees(39, 1362, 28, true));
        Attempt later = attempt(3, agrees(39, 50, 39, false));

        assertThat(ReferenceWalk.choose(List.of(first, agreed, later))).isSameAs(agreed);
    }

    @Test
    void withNoAgreementTheNonFullReferenceWithTheMostLinesLiningUpIsKept() {
        Attempt low = attempt(1, check(39, 16, 6, -339_000, false));
        Attempt high = attempt(2, check(39, 20, 11, 0, false));
        Attempt full = attempt(3, check(39, 1362, 30, 0, true));

        assertThat(ReferenceWalk.choose(List.of(low, full, high))).isSameAs(high);
    }

    @Test
    void aFullReferenceIsKeptOnlyWhenNoNonFullOneWasComparedAndTheBestOfThemWins() {
        Attempt fullLow = attempt(1, check(39, 1362, 10, 0, true));
        Attempt fullHigh = attempt(2, check(39, 900, 25, 12_000, true));

        assertThat(ReferenceWalk.choose(List.of(fullLow, fullHigh))).isSameAs(fullHigh);
        assertThat(ReferenceWalk.choose(List.of(fullLow, attempt(3, check(39, 16, 0, 0, false))))
            .reference().getId()).isEqualTo(3L);
    }

    @Test
    void aTieKeepsTheEarlierAttempt() {
        Attempt first = attempt(1, check(39, 16, 6, 0, false));
        Attempt second = attempt(2, check(39, 20, 6, 0, false));

        assertThat(ReferenceWalk.choose(List.of(first, second))).isSameAs(first);
        assertThat(ReferenceWalk.choose(List.of(second, first))).isSameAs(second);
    }

    @Test
    void aSingleAttemptKeepsItsOwnReasonsWithNothingAddedAndSoDoesAnAgreementAfterOthers() {
        Attempt only = attempt(1, check(12, 41, 12, 0, false));
        Attempt skipped = attempt(1, check(39, 1362, 5, 0, true));
        Attempt agreed = attempt(2, agrees(39, 50, 39, false));

        assertThat(ReferenceWalk.reasons(List.of(only), only)).containsExactly("a reason");
        assertThat(ReferenceWalk.reasons(List.of(skipped, agreed), agreed)).containsExactly("fine");
    }

    @Test
    void severalChecksThatAllLookLikeAnotherCutSaySoWithTheCount() {
        Attempt a = attempt(1, check(39, 16, 6, -339_000, false));
        Attempt b = attempt(2, check(39, 1362, 3, 0, true));
        Attempt c = attempt(3, check(39, 20, 9, 31_000, false));

        assertThat(ReferenceWalk.reasons(List.of(a, b, c), c)).containsExactly(
            "Tried 3 OpenSubtitles files; they look like a different cut", "a reason");
    }

    @Test
    void whenTheLastCheckWasAGenuineDisagreementTheTriedLineDoesNotBlameTheCut() {
        Attempt a = attempt(1, check(12, 16, 0, -339_000, false));
        Attempt b = attempt(2, check(12, 41, 12, 0, false));

        assertThat(ReferenceWalk.reasons(List.of(a, b), b)).containsExactly(
            "Tried 2 OpenSubtitles files; none of them agreed with this file", "a reason");
    }
}
