package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.TimingReferenceRule.FileFacts;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules of phase 3c-1, row by row: which editions qualify, which files may be a reference, when it is usable. */
class TimingReferenceRuleTest {

    private static final long RUNTIME = 100 * 60_000L;
    private static final FileFacts GOOD = new FileFacts(20, false, false);

    private static boolean qualifies(List<FileFacts> files, boolean playsEmbedded, long runtime, int parts) {
        return TimingReferenceRule.qualifies(files, playsEmbedded, runtime, parts);
    }

    @Test
    void anEditionWithOneFileOfTwentyLinesNoTrackAndARuntimeQualifies() {
        assertThat(qualifies(List.of(GOOD), false, RUNTIME, 1)).isTrue();
    }

    @Test
    void anEditionWithNoFileTheTimingCheckCoversDoesNotQualify() {
        assertThat(qualifies(List.of(), false, RUNTIME, 1)).isFalse();
    }

    @Test
    void theFileNeedsAtLeastEightCuesAndFewerThanThreeHundred() {
        assertThat(qualifies(List.of(new FileFacts(7, false, false)), false, RUNTIME, 1)).isFalse();
        assertThat(qualifies(List.of(new FileFacts(8, false, false)), false, RUNTIME, 1)).isTrue();
        assertThat(qualifies(List.of(new FileFacts(299, false, false)), false, RUNTIME, 1)).isTrue();
        assertThat(qualifies(List.of(new FileFacts(300, false, false)), false, RUNTIME, 1)).isFalse();
    }

    @Test
    void aFileTheOwnerVerifiedKeepsTheEditionOut() {
        assertThat(qualifies(List.of(GOOD, new FileFacts(20, true, false)), false, RUNTIME, 1)).isFalse();
        assertThat(qualifies(List.of(new FileFacts(20, true, false)), false, RUNTIME, 1)).isFalse();
    }

    @Test
    void anEditionThatPlaysItsEmbeddedForcedTrackDoesNotQualify() {
        assertThat(qualifies(List.of(GOOD), true, RUNTIME, 1)).isFalse();
    }

    @Test
    void anUnknownRuntimeKeepsTheEditionOutAndOneMillisecondQualifies() {
        assertThat(qualifies(List.of(GOOD), false, 0, 1)).isFalse();
        assertThat(qualifies(List.of(GOOD), false, 1, 1)).isTrue();
    }

    @Test
    void aSplitEditionQualifiesOnlyWhenEveryFileIsTimedForTheWholeEdition() {
        FileFacts whole = new FileFacts(20, false, true);

        assertThat(qualifies(List.of(whole), false, RUNTIME, 2)).isTrue();
        assertThat(qualifies(List.of(whole, new FileFacts(20, false, true)), false, RUNTIME, 3)).isTrue();
        assertThat(qualifies(List.of(GOOD), false, RUNTIME, 2)).isFalse();
        assertThat(qualifies(List.of(whole, GOOD), false, RUNTIME, 2)).isFalse();
    }

    private static OpenSubtitlesResult withFps(Double fps) {
        return new OpenSubtitlesResult(1, 2, "Release", fps, 10, false, false, null, null);
    }

    @Test
    void aFileOfAnUnknownOrAboutTwentyThreeNineSevenSixFrameRateMayBeUsed() {
        assertThat(TimingReferenceRule.plausibleFps(withFps(null))).isTrue();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.976))).isTrue();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.98))).isTrue();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.967))).isTrue();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.966))).isTrue();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.986))).isTrue();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.985))).isTrue();
    }

    @Test
    void aFileOfAnyOtherKnownFrameRateIsLeftOut() {
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.96))).isFalse();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.965))).isFalse();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.987))).isFalse();
        assertThat(TimingReferenceRule.plausibleFps(withFps(23.99))).isFalse();
        assertThat(TimingReferenceRule.plausibleFps(withFps(24.0))).isFalse();
        assertThat(TimingReferenceRule.plausibleFps(withFps(25.0))).isFalse();
        assertThat(TimingReferenceRule.plausibleFps(withFps(29.97))).isFalse();
    }

    @Test
    void aReferenceNeedsThreeHundredLines() {
        assertThat(TimingReferenceRule.usable(299, RUNTIME, RUNTIME, null)).isFalse();
        assertThat(TimingReferenceRule.usable(300, RUNTIME, RUNTIME, null)).isTrue();
        assertThat(TimingReferenceRule.usable(301, RUNTIME, RUNTIME, null)).isTrue();
    }

    @Test
    void theLastCueMustEndBeforeTheRuntimePlusSixtySeconds() {
        assertThat(TimingReferenceRule.usable(300, RUNTIME + 59_999, RUNTIME, null)).isTrue();
        assertThat(TimingReferenceRule.usable(300, RUNTIME + 60_000, RUNTIME, null)).isFalse();
        assertThat(TimingReferenceRule.usable(300, RUNTIME + 60_001, RUNTIME, null)).isFalse();
    }

    @Test
    void whenCreditsWereFoundTheLastCueMustEndAfterTheCreditsStartLessTenMinutes() {
        long credits = 95 * 60_000L;
        long limit = credits - 10 * 60_000L;

        assertThat(TimingReferenceRule.usable(300, limit - 1, RUNTIME, credits)).isFalse();
        assertThat(TimingReferenceRule.usable(300, limit, RUNTIME, credits)).isFalse();
        assertThat(TimingReferenceRule.usable(300, limit + 1, RUNTIME, credits)).isTrue();
    }

    @Test
    void whenCreditsWereNotFoundAShortEndingIsNotJudged() {
        assertThat(TimingReferenceRule.usable(300, 1_000, RUNTIME, null)).isTrue();
    }

    private static TimingFit.Result fit(int matched, int served, int offsetMs) {
        return new TimingFit.Result(TimingFit.Verdict.CHECK, offsetMs, 0, matched, served, 0, List.of());
    }

    @Test
    void aReferenceMatchingThirtyPercentOfTheLinesStopsTheWalkAndTwoOfTenDoesNot() {
        assertThat(TimingReferenceRule.matches(fit(3, 10, 0))).isTrue();
        assertThat(TimingReferenceRule.matches(fit(2, 10, 0))).isFalse();
        assertThat(TimingReferenceRule.matches(fit(30, 100, 0))).isTrue();
        assertThat(TimingReferenceRule.matches(fit(29, 100, 0))).isFalse();
    }

    @Test
    void aBestOffsetFurtherThanThirtySecondsEitherWayIsAnotherCutAndDoesNotStopTheWalk() {
        assertThat(TimingReferenceRule.matches(fit(10, 10, 30_000))).isTrue();
        assertThat(TimingReferenceRule.matches(fit(10, 10, -30_000))).isTrue();
        assertThat(TimingReferenceRule.matches(fit(10, 10, 30_001))).isFalse();
        assertThat(TimingReferenceRule.matches(fit(10, 10, -30_001))).isFalse();
    }

    private static OpenSubtitlesReference reference(int cues, Long lastCueEndMs) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setCueCount(cues);
        reference.setLastCueEndMs(lastCueEndMs);
        reference.setCuesText(SrtParser.format(List.of(new SubtitleCue(1_000, 2_000, "a"),
            new SubtitleCue(9_000, 90_500, "b"), new SubtitleCue(5_000, 6_000, "c"))));
        return reference;
    }

    @Test
    void aStoredReferencesLastCueEndIsTheKeptFigureOrElseWorkedOutFromItsText() {
        assertThat(TimingReferenceRule.lastCueEnd(reference(300, 123_456L))).isEqualTo(123_456L);
        assertThat(TimingReferenceRule.lastCueEnd(reference(300, null))).isEqualTo(90_500L);
    }

    @Test
    void aStoredReferenceIsJudgedOnItsKeptFigureAndAnOlderRowOnItsText() {
        assertThat(TimingReferenceRule.usable(reference(300, 90_500L), 90_000L, null)).isTrue();
        assertThat(TimingReferenceRule.usable(reference(300, null), 90_000L, null)).isTrue();
        assertThat(TimingReferenceRule.usable(reference(300, null), 30_000L, null)).isFalse();
        assertThat(TimingReferenceRule.usable(reference(0, 90_500L), 90_000L, null)).isFalse();
    }
}
