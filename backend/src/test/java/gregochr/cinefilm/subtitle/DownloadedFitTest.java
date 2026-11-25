package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.DownloadedFit.Choice;
import gregochr.cinefilm.subtitle.DownloadedFit.Reference;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The verdicts against a downloaded file and the choice among several, at their boundaries. */
class DownloadedFitTest {

    private static final Reference REFERENCE = new Reference(5012, "Taken.2008.BluRay", 1_000, List.of(1L));

    private static Result fit(Verdict verdict, int offset, int matched, int served, int residual) {
        return new Result(verdict, offset, (double) matched / served, matched, served, residual, List.of());
    }

    private static Result judge(Verdict verdict, int offset, int served, Reference reference) {
        return DownloadedFit.judge(fit(verdict, offset, served, served, 10), reference);
    }

    @Test
    void anOffsetOfOneSecondEitherWayStaysAlignedWithTheReleaseInTheSentence() {
        for (int offset : new int[] {1_000, -1_000, 0}) {
            Result result = judge(Verdict.ALIGNED, offset, 12, REFERENCE);

            assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
            assertThat(result.reasons())
                .containsExactly("Lines match a full English file from OpenSubtitles (Taken.2008.BluRay)");
            assertThat(result.fittedOffsetMs()).isEqualTo(offset);
        }
    }

    @Test
    void theSameMatchOneMillisecondFurtherOutIsCheckWithTheSecondsApart() {
        for (int offset : new int[] {1_001, -1_001}) {
            Result result = judge(Verdict.ALIGNED, offset, 12, REFERENCE);

            assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
            assertThat(result.reasons()).containsExactly("Lines match a full English file from OpenSubtitles "
                + "(Taken.2008.BluRay) but 1.0 s apart: one of them is timed for another release");
        }
        assertThat(judge(Verdict.ALIGNED, 4_250, 12, REFERENCE).reasons().get(0)).contains("but 4.3 s apart");
    }

    private static final String FULL = "Looks like full subtitles, not forced";

    private static Reference lines(int cueCount) {
        return new Reference(5012, "Taken.2008.BluRay", cueCount, List.of(1L));
    }

    private static final String ALIGNED_REASON =
        "Lines match a full English file from OpenSubtitles (Taken.2008.BluRay)";

    private static void assertAligned(Result result) {
        assertThat(result.verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(result.reasons()).containsExactly(ALIGNED_REASON);
    }

    private static void assertFull(Result result) {
        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).containsExactly(FULL);
    }

    @Test
    void aFileAsLongAsALargeReferenceIsFullEvenWhenTimingFitsAndEvenWhenItDoesNot() {
        for (Verdict verdict : new Verdict[] {Verdict.ALIGNED, Verdict.CHECK}) {
            assertFull(judge(verdict, 0, 1_300, lines(1_300)));
        }
    }

    @Test
    void aGenuineForcedFileOfThreeHundredSixteenLinesIsAlignedAgainstTheEqualizerReferenceAndALargeOne() {
        assertAligned(judge(Verdict.ALIGNED, 10, 316, lines(348)));
        assertAligned(judge(Verdict.ALIGNED, 10, 316, lines(1_100)));
    }

    @Test
    void againstAThousandLineReferenceFiveHundredLinesIsAlignedAndFiveHundredAndOneIsFull() {
        assertAligned(judge(Verdict.ALIGNED, 0, 500, lines(1_000)));
        assertFull(judge(Verdict.ALIGNED, 0, 501, lines(1_000)));
    }

    @Test
    void theHalfTestIsExemptUnderSixHundredReferenceLinesAndAppliesFromSixHundred() {
        assertAligned(judge(Verdict.ALIGNED, 0, 500, lines(599)));
        assertAligned(judge(Verdict.ALIGNED, 0, 300, lines(600)));
        assertFull(judge(Verdict.ALIGNED, 0, 301, lines(600)));
    }

    @Test
    void underSixHundredReferenceLinesOnlyMoreThanTwiceTheReferenceIsFull() {
        assertAligned(judge(Verdict.ALIGNED, 0, 600, lines(300)));
        assertFull(judge(Verdict.ALIGNED, 0, 601, lines(300)));
    }

    @Test
    void theThreeHundredLineFloorHoldsForASmallReferenceThoughProductionReferencesAreThreeHundredOrMore() {
        assertAligned(judge(Verdict.ALIGNED, 0, 299, lines(100)));
        assertFull(judge(Verdict.ALIGNED, 0, 300, lines(100)));
    }


    private static Result looseFit(int offset, int matched, int served, int residual, List<String> reasons) {
        return new Result(Verdict.CHECK, offset, (double) matched / served, matched, served, residual, reasons);
    }

    private static final String LOOSE_REASON = "The lines line up only loosely (typically 170 ms off)";
    private static final String FALLBACK = "The lines do not line up well enough with the full English file from "
        + "OpenSubtitles (Taken.2008.BluRay) to tell: it may be another cut or timed differently";

    @Test
    void aLooseMatchOfNearlyEveryLineSaysSoWithTheReleaseAndTheResidual() {
        Result result = DownloadedFit.judge(looseFit(170, 74, 77, 170, List.of(LOOSE_REASON)), REFERENCE);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).containsExactly("Lines match a full English file from OpenSubtitles "
            + "(Taken.2008.BluRay) but only loosely (typically 170 ms off): one of them may be timed slightly "
            + "differently");
    }

    @Test
    void aLooseMatchAtTheEightyPercentAndOffsetEdgesStillSaysSo() {
        assertThat(TimingFit.enoughMatched(62, 77)).isTrue();
        assertThat(DownloadedFit.judge(looseFit(10_000, 62, 77, 121, List.of(LOOSE_REASON)), REFERENCE).reasons()
            .get(0)).contains("but only loosely (typically 121 ms off)");
    }

    @Test
    void everyOtherCheckKeepsTheFallbackSentence() {
        assertThat(TimingFit.enoughMatched(61, 77)).isFalse();
        List<Result> others = List.of(
            looseFit(170, 74, 77, 170, List.of(LOOSE_REASON, "Another reason")),
            looseFit(170, 74, 77, 120, List.of(LOOSE_REASON)),
            looseFit(170, 61, 77, 170, List.of(LOOSE_REASON)),
            looseFit(10_001, 74, 77, 170, List.of(LOOSE_REASON)));

        for (Result fit : others) {
            Result result = DownloadedFit.judge(fit, REFERENCE);

            assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
            assertThat(result.reasons()).containsExactly(FALLBACK);
        }
    }

    @Test
    void aMatchThatTimingFitDoesNotCallAlignedIsCheckWithReasonsThatNameTheFileAndDoNotBlameTheForcedOne() {
        Result result = DownloadedFit.judge(fit(Verdict.CHECK, 0, 4, 12, 10), REFERENCE);

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).singleElement().asString().contains("Taken.2008.BluRay")
            .contains("may be another cut or timed differently").doesNotContain("forced file");
        assertThat(DownloadedFit.judge(fit(Verdict.CHECK, 0, 4, 12, 10), new Reference(5013, null, 1_000,
            List.of(1L))).reasons().get(0)).contains("file 5013");
    }

    @Test
    void tooFewLinesStaysTooFewAndNoReferenceIsLeftAlone() {
        Result few = fit(Verdict.TOO_FEW, 0, 0, 7, 0);

        assertThat(DownloadedFit.judge(few, REFERENCE)).isEqualTo(few);
        Result none = fit(Verdict.NO_REFERENCE, 0, 0, 7, 0);
        assertThat(DownloadedFit.judge(none, REFERENCE)).isEqualTo(none);
    }

    /** An irregular reference (a regular grid would match at many offsets). */
    private static List<Long> irregular(int count) {
        List<Long> starts = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            starts.add(i * 7_000L + (long) i * i * 131 % 4_001);
        }
        return starts;
    }

    /** {@code total} cues from the reference: the first {@code onTime} exactly, the rest each off by its own amount. */
    private static List<Long> served(List<Long> reference, int total, int onTime) {
        List<Long> starts = new ArrayList<>();
        for (int j = 0; j < total; j++) {
            starts.add(reference.get(20 * j + 5) + (j < onTime ? 0 : 1_500L + j * 1_000L));
        }
        return starts;
    }

    @Test
    void sevenCuesIsTooFewAndEightCuesMatchingIsAligned() {
        List<Long> reference = irregular(400);
        Reference ref = new Reference(5012, "Taken", 400, reference);

        assertThat(DownloadedFit.judge(TimingFit.fit(served(reference, 7, 7), reference), ref).verdict())
            .isEqualTo(Verdict.TOO_FEW);
        assertThat(DownloadedFit.judge(TimingFit.fit(served(reference, 8, 8), reference), ref).verdict())
            .isEqualTo(Verdict.ALIGNED);
    }

    @Test
    void aShareOfEightyPercentIsAlignedAndSeventyPercentIsCheck() {
        List<Long> reference = irregular(400);
        Reference ref = new Reference(5012, "Taken", 400, reference);

        assertThat(DownloadedFit.judge(TimingFit.fit(served(reference, 10, 8), reference), ref).verdict())
            .isEqualTo(Verdict.ALIGNED);
        assertThat(DownloadedFit.judge(TimingFit.fit(served(reference, 10, 7), reference), ref).verdict())
            .isEqualTo(Verdict.CHECK);
    }

    private static Choice choice(long fileId, Verdict verdict, int matched, int served, int residual) {
        Reference reference = new Reference(fileId, "Release " + fileId, 1_000, List.of(1L));
        return new Choice(reference, fit(verdict, 0, matched, served, residual));
    }

    @Test
    void anAlignedFitBeatsAFitWithALargerShare() {
        Choice aligned = choice(5013, Verdict.ALIGNED, 8, 10, 100);
        Choice larger = choice(5012, Verdict.CHECK, 9, 10, 10);

        assertThat(DownloadedFit.compare(aligned, larger)).isNegative();
        assertThat(DownloadedFit.compare(larger, aligned)).isPositive();
    }

    @Test
    void theLargerMatchedShareWinsByExactCrossMultiplicationAndEqualSharesTie() {
        Choice seventyOf10 = choice(5013, Verdict.CHECK, 7, 10, 100);
        Choice eightOf12 = choice(5012, Verdict.CHECK, 8, 12, 10);
        Choice sixOf10 = choice(5012, Verdict.CHECK, 6, 10, 100);
        Choice twelveOf20 = choice(5012, Verdict.CHECK, 12, 20, 100);

        assertThat(DownloadedFit.compare(seventyOf10, eightOf12)).isNegative();
        assertThat(DownloadedFit.compare(sixOf10, twelveOf20)).isZero();
    }

    @Test
    void equalSharesAreSeparatedByTheSmallerMedianResidualThenByTheLowerFileId() {
        Choice tight = choice(5013, Verdict.CHECK, 6, 10, 20);
        Choice loose = choice(5012, Verdict.CHECK, 6, 10, 21);
        Choice lowerId = choice(5012, Verdict.CHECK, 6, 10, 20);

        assertThat(DownloadedFit.compare(tight, loose)).isNegative();
        assertThat(DownloadedFit.compare(lowerId, tight)).isNegative();
        assertThat(DownloadedFit.compare(tight, lowerId)).isPositive();
    }

    @Test
    void chooseKeepsTheReferenceTheFileFitsAndOnAnExactTieTheLowerFileId() {
        List<Long> reference = irregular(400);
        List<Long> other = irregular(400).stream().map(start -> start + 55_000).toList();
        List<Long> served = served(reference, 10, 10);

        Choice fitting = DownloadedFit.choose(served, List.of(new Reference(5012, "Other cut", 400, other),
            new Reference(5013, "Right cut", 400, reference)));
        Choice tie = DownloadedFit.choose(served, List.of(new Reference(5014, "Copy", 400, reference),
            new Reference(5013, "Right cut", 400, reference)));

        assertThat(fitting.reference().osFileId()).isEqualTo(5013L);
        assertThat(fitting.result().verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(tie.reference().osFileId()).isEqualTo(5013L);
    }

    @Test
    void aReferenceThatCarriesItsOwnServedStartsIsFittedWithThemInsteadOfTheGivenOnes() {
        List<Long> reference = irregular(400);
        List<Long> originals = served(reference, 10, 10);
        List<Long> filmTimeline = originals.stream().map(start -> start + 90_000).toList();

        Choice choice = DownloadedFit.choose(filmTimeline,
            List.of(new Reference(5012, "Same timeline", 400, reference, originals)));

        assertThat(choice.result().verdict()).isEqualTo(Verdict.ALIGNED);
        assertThat(DownloadedFit.choose(filmTimeline, List.of(new Reference(5012, "Same timeline", 400, reference)))
            .result().verdict()).isNotEqualTo(Verdict.ALIGNED);
    }
}
