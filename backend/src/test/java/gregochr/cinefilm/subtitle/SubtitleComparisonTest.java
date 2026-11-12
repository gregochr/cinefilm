package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The comparison of the served forced subtitle with OpenSubtitles' file: each case has an exact expected result. */
class SubtitleComparisonTest {

    private static final String SUBSET_AGREES =
        "OpenSubtitles only had full subtitles; this file's lines are in them and in time";
    private static final String SUBSET_CHECK =
        "OpenSubtitles only had full subtitles, and this file's lines don't line up with them";
    private static final String SHARE_60 =
        "Only 60% of this file's lines line up with OpenSubtitles' (within 2 seconds)";

    /** The result for {@code served} lines against {@code reference}, the matched count being the share of served. */
    private static Result result(int served, int reference, double share, int offset, Verdict verdict,
                                 List<String> reasons) {
        return new Result(served, reference, share, offset, verdict, reasons, (int) Math.round(served * share),
            reference >= SubtitleComparison.FULL_LINES);
    }

    private static List<SubtitleCue> cues(long... starts) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (long start : starts) {
            cues.add(new SubtitleCue(start, start + 1000, "x"));
        }
        return cues;
    }

    /** {@code count} cues, the first at {@code first} and one every {@code step} milliseconds. */
    private static long[] every(long first, long step, int count) {
        long[] starts = new long[count];
        for (int i = 0; i < count; i++) {
            starts[i] = first + step * i;
        }
        return starts;
    }

    private static long[] shifted(long[] starts, long by) {
        long[] moved = new long[starts.length];
        for (int i = 0; i < starts.length; i++) {
            moved[i] = starts[i] + by;
        }
        return moved;
    }

    private static long[] join(long[] first, long[] second) {
        long[] all = new long[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static final long[] FIVE = {10_000, 20_000, 30_000, 40_000, 50_000};

    @Test
    void identicalFilesAgreeAtOffsetZeroWithNoReasons() {
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(FIVE)))
            .isEqualTo(result(5, 5, 1.0, 0, Verdict.AGREES, List.of()));
    }

    @Test
    void identicalTextWithDifferentWordingIsStillAnAgreementBecauseOnlyTheTimesAreCompared() {
        List<SubtitleCue> served = List.of(new SubtitleCue(10_000, 12_000, "Hello"));
        List<SubtitleCue> reference = List.of(new SubtitleCue(10_000, 11_000, "Bonjour"));

        assertThat(SubtitleComparison.compare(served, reference))
            .isEqualTo(result(1, 1, 1.0, 0, Verdict.AGREES, List.of()));
    }

    @Test
    void aConstantShiftOfOnePointTwoSecondsIsFoundExactlyAndOnlyHintedAboutNeverAppliedOrJudged() {
        Result result = SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 1200)));

        assertThat(result).isEqualTo(result(5, 5, 1.0, 1200, Verdict.AGREES,
            List.of("OpenSubtitles' file is 1.2 s later")));
    }

    @Test
    void aConstantShiftTheOtherWayIsEarlier() {
        Result result = SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, -1200)));

        assertThat(result).isEqualTo(result(5, 5, 1.0, -1200, Verdict.AGREES,
            List.of("OpenSubtitles' file is 1.2 s earlier")));
    }

    @Test
    void theOffsetHintAppearsAtFiveHundredMillisecondsAndNotAtFourHundred() {
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 400))))
            .isEqualTo(result(5, 5, 1.0, 400, Verdict.AGREES, List.of()));
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 500))))
            .isEqualTo(result(5, 5, 1.0, 500, Verdict.AGREES, List.of("OpenSubtitles' file is 0.5 s later")));
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, -400))))
            .isEqualTo(result(5, 5, 1.0, -400, Verdict.AGREES, List.of()));
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, -500))))
            .isEqualTo(result(5, 5, 1.0, -500, Verdict.AGREES,
                List.of("OpenSubtitles' file is 0.5 s earlier")));
    }

    @Test
    void theHintIsKeptAlongsideTheReasonsWhenTheVerdictIsCheck() {
        long[] all = every(10_000, 10_000, 41);
        long[] twelve = shifted(java.util.Arrays.copyOf(all, 12), -1200);

        Result result = SubtitleComparison.compare(cues(twelve), cues(all));

        assertThat(result).isEqualTo(result(12, 41, 1.0, 1200, Verdict.CHECK,
            List.of("OpenSubtitles has 41 lines, this file has 12", "OpenSubtitles' file is 1.2 s later")));
    }

    @Test
    void theHintIsWrittenInSecondsToOneDecimal() {
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 2300))).reasons())
            .containsExactly("OpenSubtitles' file is 2.3 s later");
        assertThat(SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 600_000))).reasons())
            .containsExactly("OpenSubtitles' file is 600.0 s later");
    }

    @Test
    void anIncompleteFileTwelveOfFortyOneLinesIsCheckedWithTheCountsInTheReason() {
        long[] all = every(10_000, 10_000, 41);

        Result result = SubtitleComparison.compare(cues(java.util.Arrays.copyOf(all, 12)), cues(all));

        assertThat(result).isEqualTo(result(12, 41, 1.0, 0, Verdict.CHECK,
            List.of("OpenSubtitles has 41 lines, this file has 12")));
    }

    @Test
    void theIncompleteBoundaryIsHalfTheReferenceExactlyAgreesAndOneFewerIsChecked() {
        long[] forty = every(10_000, 10_000, 40);
        long[] fortyOne = every(10_000, 10_000, 41);

        assertThat(SubtitleComparison.compare(cues(java.util.Arrays.copyOf(forty, 20)), cues(forty)))
            .isEqualTo(result(20, 40, 1.0, 0, Verdict.AGREES, List.of()));
        assertThat(SubtitleComparison.compare(cues(java.util.Arrays.copyOf(forty, 19)), cues(forty)))
            .isEqualTo(result(19, 40, 1.0, 0, Verdict.CHECK,
                List.of("OpenSubtitles has 40 lines, this file has 19")));
        assertThat(SubtitleComparison.compare(cues(java.util.Arrays.copyOf(fortyOne, 21)), cues(fortyOne)))
            .isEqualTo(result(21, 41, 1.0, 0, Verdict.AGREES, List.of()));
        assertThat(SubtitleComparison.compare(cues(java.util.Arrays.copyOf(fortyOne, 20)), cues(fortyOne)))
            .isEqualTo(result(20, 41, 1.0, 0, Verdict.CHECK,
                List.of("OpenSubtitles has 41 lines, this file has 20")));
    }

    @Test
    void aServedFileOfOneHundredTwentyLinesAgainstFortyOneIsCheckedButNotCalledFullSubtitles() {
        // The served file has a cue every 5 s (120 of them); OpenSubtitles' has every other 10 s (41): only the
        // cues that coincide (41 of 120) line up, at offset 0 and also at +-5 s, and the smallest offset wins.
        Result result = SubtitleComparison.compare(cues(every(5_000, 5_000, 120)), cues(every(10_000, 10_000, 41)));

        assertThat(result).isEqualTo(result(120, 41, 41.0 / 120, 0, Verdict.CHECK, List.of(
            "Only 34% of this file's lines line up with OpenSubtitles' (within 2 seconds)",
            "This file has 120 lines, OpenSubtitles has 41")));
    }

    @Test
    void theTooManyLinesBoundaryIsTwiceTheReferenceExactlyAgreesAndOneMoreIsChecked() {
        long[] ten = every(10_000, 10_000, 10);

        // 20 served cues, each within a second of a reference cue (10 exactly, 10 half a second later).
        Result atTwice = SubtitleComparison.compare(cues(join(ten, shifted(ten, 500))), cues(ten));
        // The same and one more cue exactly on the last reference cue: 21 served lines against 10.
        Result overTwice = SubtitleComparison.compare(cues(join(join(ten, shifted(ten, 500)), new long[] {100_000})),
            cues(ten));

        assertThat(atTwice).isEqualTo(result(20, 10, 1.0, 0, Verdict.AGREES, List.of()));
        assertThat(overTwice.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(overTwice.reasons()).containsExactly(
            "This file has 21 lines, OpenSubtitles has 10");
        assertThat(overTwice.matchedShare()).isEqualTo(1.0);
        assertThat(overTwice.bestOffsetMs()).isZero();
    }

    @Test
    void theWrongCutHalfTheLinesAHugeOffsetApartHasALowShareAndIsChecked() {
        long[] firstHalf = every(10_000, 10_000, 10);
        long[] secondHalf = shifted(every(110_000, 10_000, 10), 300_000);

        // Served: 10 lines that line up, then 10 lines five minutes later than OpenSubtitles' (which has 20).
        // Offset 0 and offset -300 s both match 10 lines exactly; the smaller offset wins.
        Result result = SubtitleComparison.compare(cues(join(firstHalf, secondHalf)), cues(every(10_000, 10_000, 20)));

        assertThat(result).isEqualTo(result(20, 20, 0.5, 0, Verdict.CHECK,
            List.of("Only 50% of this file's lines line up with OpenSubtitles' (within 2 seconds)")));
    }

    @Test
    void theShareBoundaryIsSeventyPercentExactlyAgreesAndSixtyPercentIsChecked() {
        long[] ten = every(10_000, 10_000, 10);
        long[] far = {1_000_000, 1_010_000, 1_020_000};

        Result seventy = SubtitleComparison.compare(cues(join(java.util.Arrays.copyOf(ten, 7), far)), cues(ten));
        Result sixty = SubtitleComparison.compare(
            cues(join(java.util.Arrays.copyOf(ten, 6), join(far, new long[] {1_030_000}))), cues(ten));
        Result eighty = SubtitleComparison.compare(
            cues(join(java.util.Arrays.copyOf(ten, 8), new long[] {1_000_000, 1_010_000})), cues(ten));

        assertThat(seventy).isEqualTo(result(10, 10, 0.7, 0, Verdict.AGREES, List.of()));
        assertThat(sixty).isEqualTo(result(10, 10, 0.6, 0, Verdict.CHECK, List.of(SHARE_60)));
        assertThat(eighty).isEqualTo(result(10, 10, 0.8, 0, Verdict.AGREES, List.of()));
    }

    @Test
    void aSharePercentageIsRoundedDownInTheReason() {
        // 2 of 3 lines match: 66.67%, written as 66%.
        Result result = SubtitleComparison.compare(cues(10_000, 20_000, 2_000_000), cues(10_000, 20_000, 30_000));

        assertThat(result.reasons()).containsExactly(
            "Only 66% of this file's lines line up with OpenSubtitles' (within 2 seconds)");
        assertThat(result.matchedShare()).isEqualTo(2.0 / 3);
    }

    @Test
    void aCueTwoSecondsAwayMatchesAndOneMillisecondMoreDoesNotSoTheOffsetBucketTakesOver() {
        // 2000 ms is inside the window (inclusive): offset 0 matches both, ties with offset 2000, and 0 wins.
        assertThat(SubtitleComparison.compare(cues(10_000, 100_000), cues(12_000, 100_000)))
            .isEqualTo(result(2, 2, 1.0, 0, Verdict.AGREES, List.of()));
        // 2001 ms is outside it: only an offset of +2 s lines both cues up.
        assertThat(SubtitleComparison.compare(cues(10_000, 100_000), cues(12_001, 100_000)))
            .isEqualTo(result(2, 2, 1.0, 2000, Verdict.AGREES, List.of("OpenSubtitles' file is 2.0 s later")));
    }

    @Test
    void emptyInputsAreNeverAnAgreement() {
        assertThat(SubtitleComparison.compare(List.of(), List.of()))
            .isEqualTo(result(0, 0, 0.0, 0, Verdict.CHECK,
                List.of("OpenSubtitles' file has no lines to compare with")));
        assertThat(SubtitleComparison.compare(cues(FIVE), List.of()))
            .isEqualTo(result(5, 0, 0.0, 0, Verdict.CHECK,
                List.of("OpenSubtitles' file has no lines to compare with")));
        assertThat(SubtitleComparison.compare(List.of(), cues(FIVE)))
            .isEqualTo(result(0, 5, 0.0, 0, Verdict.CHECK,
                List.of("OpenSubtitles has 5 lines, this file has 0")));
        assertThat(SubtitleComparison.compare(List.of(), cues(10_000)))
            .isEqualTo(result(0, 1, 0.0, 0, Verdict.CHECK, List.of("OpenSubtitles has 1 line, this file has 0")));
    }

    @Test
    void whenTwoOffsetsFitEquallyTheSmallerAbsoluteOffsetWins() {
        // +300 ms and -500 ms both land the single cue exactly on a reference cue.
        assertThat(SubtitleComparison.compare(cues(10_000), cues(10_300, 9_500)))
            .isEqualTo(result(1, 2, 1.0, 300, Verdict.AGREES, List.of()));
    }

    @Test
    void whenTwoOffsetsAreEquallyFarFromZeroTheLowerOneWins() {
        assertThat(SubtitleComparison.compare(cues(10_000), cues(10_500, 9_500)))
            .isEqualTo(result(1, 2, 1.0, -500, Verdict.AGREES, List.of("OpenSubtitles' file is 0.5 s earlier")));
    }

    @Test
    void theBestFitBeatsASmallerOffsetThatOnlyMatchesInsideTheWindow() {
        // Both offset 0 and +1200 match every cue within 2 s; +1200 matches them exactly, so it is the best fit.
        Result result = SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 1200)));

        assertThat(result.bestOffsetMs()).isEqualTo(1200);
    }

    @Test
    void whenNoTwoCuesAreWithinTenMinutesOfEachOtherNothingLinesUp() {
        assertThat(SubtitleComparison.compare(cues(0), cues(2_000_000)))
            .isEqualTo(result(1, 1, 0.0, 0, Verdict.CHECK,
                List.of("Only 0% of this file's lines line up with OpenSubtitles' (within 2 seconds)")));
    }

    @Test
    void theOrderOfTheCuesInTheInputsDoesNotMatter() {
        List<SubtitleCue> served = cues(50_000, 10_000, 40_000, 20_000, 30_000);
        List<SubtitleCue> reference = cues(shifted(new long[] {30_000, 50_000, 10_000, 20_000, 40_000}, 1200));

        assertThat(SubtitleComparison.compare(served, reference)).isEqualTo(
            SubtitleComparison.compare(cues(FIVE), cues(shifted(FIVE, 1200))));
    }

    @Test
    void aFullLengthFilmOfThreeThousandCuesIsComparedAndAgrees() {
        long[] starts = every(5_000, 2_000, 3000);

        assertThat(SubtitleComparison.compare(cues(starts), cues(shifted(starts, 700))))
            .isEqualTo(result(3000, 3000, 1.0, 700, Verdict.AGREES,
                List.of(SUBSET_AGREES, "OpenSubtitles' file is 0.7 s later")));
    }

    @Test
    void offsetsAreBucketedToOneHundredMillisecondsWithHalfRoundedUp() {
        assertThat(SubtitleComparison.candidates(new long[] {0}, new long[] {-151, -150, -149, 149, 150, 151}, 10))
            .containsExactly(-100L, 200L, 100L, -200L);
    }

    @Test
    void candidatesAreOrderedByHowManyPairsFallInTheBucketThenBySmallerAbsoluteOffsetThenLower() {
        assertThat(SubtitleComparison.candidates(new long[] {0, 1000}, new long[] {5000, 6000}, 10))
            .containsExactly(5000L, 4000L, 6000L);
    }

    @Test
    void onlyDifferencesWithinTenMinutesEitherWayAreCandidates() {
        assertThat(SubtitleComparison.candidates(new long[] {0}, new long[] {600_049, 600_050, 1_000_000}, 10))
            .containsExactly(600_000L);
        assertThat(SubtitleComparison.candidates(new long[] {0}, new long[] {-1_000_000, -600_051, -600_050}, 10))
            .containsExactly(-600_000L);
        assertThat(SubtitleComparison.candidates(new long[] {0}, new long[] {600_000}, 10))
            .containsExactly(600_000L);
    }

    @Test
    void theCandidateCountIsCappedAtFiveHundredKeepingTheMostFrequentThenTheSmallestOffsets() {
        long[] reference = every(100, 100, 600);

        List<Long> candidates = SubtitleComparison.candidates(new long[] {0}, reference,
            SubtitleComparison.MAX_CANDIDATES);

        assertThat(SubtitleComparison.MAX_CANDIDATES).isEqualTo(500);
        assertThat(candidates).hasSize(500);
        assertThat(candidates.get(0)).isEqualTo(100L);
        assertThat(candidates.get(499)).isEqualTo(50_000L);
    }

    @Test
    void aCappedSearchCanMissAFitThatTheFullSearchFinds() {
        // Four jittered pairs: every candidate bucket has one pair. Trying only the single smallest offset (+1000)
        // gives total distance 1800; trying all of them finds +1300 (1200).
        List<SubtitleCue> served = cues(0, 100_000, 200_000, 300_000);
        List<SubtitleCue> reference = cues(1_000, 101_300, 201_600, 301_900);

        assertThat(SubtitleComparison.compare(served, reference, 1).bestOffsetMs()).isEqualTo(1000);
        assertThat(SubtitleComparison.compare(served, reference).bestOffsetMs()).isEqualTo(1300);
    }

    @Test
    void theCapOfFiveHundredIsAppliedByTheDefaultComparison() {
        // The only fitting offset (+1 s, matching all 3 cues exactly) has 3 pairs, so it is among the most frequent
        // buckets however many one-pair buckets there are: 600 reference cues make hundreds of them.
        long[] served = {0, 50_000, 100_000};
        long[] reference = join(shifted(served, 1000), every(200_100, 100, 600));

        Result result = SubtitleComparison.compare(cues(served), cues(reference));

        assertThat(result.matchedShare()).isEqualTo(1.0);
        assertThat(result.bestOffsetMs()).isEqualTo(1000);
    }

    // ---- 2e: a full reference ("foreign parts only" that is really full subtitles) is judged by timing only ----

    /** A reference of {@code count} cues, one every 10 s from 10 s. */
    private static List<SubtitleCue> reference(int count) {
        return cues(every(10_000, 10_000, count));
    }

    @Test
    void aReferenceOfTwoHundredNinetyNineCuesIsJudgedForCompletenessAndThreeHundredAsFullSubtitles() {
        List<SubtitleCue> served = cues(every(10_000, 10_000, 100));

        Result notFull = SubtitleComparison.compare(served, reference(299));
        Result full = SubtitleComparison.compare(served, reference(300));

        assertThat(notFull.fullReference()).isFalse();
        assertThat(notFull.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(notFull.reasons()).containsExactly("OpenSubtitles has 299 lines, this file has 100");
        assertThat(full).isEqualTo(result(100, 300, 1.0, 0, Verdict.AGREES, List.of(SUBSET_AGREES)));
        assertThat(full.matched()).isEqualTo(100);
        assertThat(full.fullReference()).isTrue();
    }

    @Test
    void aFullReferenceWithTheServedLinesInTimeAgreesWhateverTheCounts() {
        // 39 served lines against 1,362: far fewer than half, and still no completeness reason.
        assertThat(SubtitleComparison.compare(cues(every(10_000, 10_000, 39)), reference(1362)))
            .isEqualTo(result(39, 1362, 1.0, 0, Verdict.AGREES, List.of(SUBSET_AGREES)));
    }

    @Test
    void theSubsetShareBoundaryIsSeventyPercentExactlyAgreesAndOneLineFewerIsChecked() {
        long[] far = {1_000_000_000L, 1_000_010_000L, 1_000_020_000L};
        long[] ten = every(10_000, 10_000, 10);

        // 7 of 10 served lines are in the reference, 3 are nowhere near.
        Result seventy = SubtitleComparison.compare(cues(join(java.util.Arrays.copyOf(ten, 7), far)), reference(300));
        // 6 of 10.
        Result sixty = SubtitleComparison.compare(
            cues(join(java.util.Arrays.copyOf(ten, 6), join(far, new long[] {1_000_030_000L}))), reference(300));

        assertThat(seventy).isEqualTo(result(10, 300, 0.7, 0, Verdict.AGREES, List.of(SUBSET_AGREES)));
        assertThat(sixty).isEqualTo(result(10, 300, 0.6, 0, Verdict.CHECK, List.of(SUBSET_CHECK)));
    }

    @Test
    void theSubsetShareIsComparedInWholeNumbersSoThirtyOfFortyThreeIsNotSeventyPercent() {
        // 30 of 43 is 69.77%: under seventy. 31 of 44 is 70.45%: over. No double subtraction is involved.
        Result under = SubtitleComparison.compare(
            cues(join(every(10_000, 10_000, 30), every(1_000_000_000L, 10_000, 13))), reference(300));
        Result over = SubtitleComparison.compare(
            cues(join(every(10_000, 10_000, 31), every(1_000_000_000L, 10_000, 13))), reference(300));

        assertThat(under.matched()).isEqualTo(30);
        assertThat(under.servedCount()).isEqualTo(43);
        assertThat(under.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(over.matched()).isEqualTo(31);
        assertThat(over.servedCount()).isEqualTo(44);
        assertThat(over.verdict()).isEqualTo(Verdict.AGREES);
    }

    @Test
    void theSubsetOffsetBoundaryIsTenSecondsExactlyAgreesAndTenPointOneIsChecked() {
        List<SubtitleCue> served = cues(every(10_000, 10_000, 20));

        Result ten = SubtitleComparison.compare(served, cues(shifted(every(10_000, 10_000, 300), 10_000)));
        Result tenPointOne = SubtitleComparison.compare(served, cues(shifted(every(10_000, 10_000, 300), 10_100)));

        assertThat(ten.bestOffsetMs()).isEqualTo(10_000);
        assertThat(ten.verdict()).isEqualTo(Verdict.AGREES);
        assertThat(ten.reasons()).containsExactly(SUBSET_AGREES, "OpenSubtitles' file is 10.0 s later");
        assertThat(tenPointOne.bestOffsetMs()).isEqualTo(10_100);
        assertThat(tenPointOne.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(tenPointOne.reasons()).containsExactly(SUBSET_CHECK, "OpenSubtitles' file is 10.1 s later");
    }

    @Test
    void aFullReferenceNeverAgreesWithAServedFileThatHasNoLines() {
        assertThat(SubtitleComparison.compare(List.of(), reference(300)))
            .isEqualTo(result(0, 300, 0.0, 0, Verdict.CHECK, List.of(SUBSET_CHECK)));
    }

    @Test
    void theProbablyFullSubtitlesWordingNeedsAServedFileOfThreeHundredLines() {
        List<SubtitleCue> ref = cues(every(10_000, 10_000, 20));

        Result under = SubtitleComparison.compare(cues(every(10_000, 10_000, 299)), ref);
        Result at = SubtitleComparison.compare(cues(every(10_000, 10_000, 300)), ref);

        assertThat(under.reasons()).contains("This file has 299 lines, OpenSubtitles has 20");
        assertThat(under.reasons()).noneMatch(reason -> reason.contains("full subtitles"));
        assertThat(at.reasons()).contains("This file has 300 lines, OpenSubtitles has 20: probably full subtitles, "
            + "not forced");
    }

    @Test
    void aThirtyNineLineFileAgainstSixteenLinesIsNeverCalledFullSubtitles() {
        // Taken 2 in production: 39 lines against 16.
        Result result = SubtitleComparison.compare(cues(every(10_000, 10_000, 39)), cues(every(500_000, 10_000, 16)));

        assertThat(result.verdict()).isEqualTo(Verdict.CHECK);
        assertThat(result.reasons()).contains("This file has 39 lines, OpenSubtitles has 16");
        assertThat(result.reasons()).noneMatch(reason -> reason.contains("full subtitles"));
    }

    @Test
    void withReasonsKeepsEverythingElse() {
        Result original = result(5, 5, 1.0, 0, Verdict.AGREES, List.of("a"));

        assertThat(original.withReasons(List.of("b", "c")))
            .isEqualTo(new Result(5, 5, 1.0, 0, Verdict.AGREES, List.of("b", "c"), 5, false));
    }
}
