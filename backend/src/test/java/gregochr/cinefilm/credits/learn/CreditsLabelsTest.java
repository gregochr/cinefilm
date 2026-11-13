package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.credits.learn.LearnFixtures.credits;
import static gregochr.cinefilm.credits.learn.LearnFixtures.match;
import static gregochr.cinefilm.credits.learn.LearnFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A 6,000,000 ms file has a 35 minute window, so its window starts at 3,900,000. */
class CreditsLabelsTest {

    private static final long D = 6_000_000;
    private static final long WINDOW_START = 3_900_000;

    private static FileLabel label(ExportRow row, ArchiveMatch... matches) {
        return CreditsLabels.label(row, List.of(matches), false);
    }

    private static LeftOut boundary(long from, long to) {
        return new LeftOut(from, to, LeftOutReason.BOUNDARY);
    }

    private static LeftOut after(long from, long to) {
        return new LeftOut(from, to, LeftOutReason.AFTER_LAST_CREDITS);
    }

    /** The three lists must be disjoint and cover exactly [windowStart, duration). */
    private static void assertCovers(FileLabel label, long windowStart, long duration) {
        List<long[]> all = new ArrayList<>();
        label.credits().forEach(s -> all.add(new long[] {s.fromMs(), s.toMs()}));
        label.film().forEach(s -> all.add(new long[] {s.fromMs(), s.toMs()}));
        label.leftOut().forEach(s -> all.add(new long[] {s.fromMs(), s.toMs()}));
        all.sort(Comparator.comparingLong(a -> a[0]));
        long at = windowStart;
        for (long[] span : all) {
            assertThat(span[0]).isEqualTo(at);
            assertThat(span[1]).isGreaterThan(span[0]);
            at = span[1];
        }
        assertThat(at).isEqualTo(duration);
    }

    @Test
    void oneMarkerGivesFilmThenBoundaryCreditsBoundaryAndNothingAfter() {
        FileLabel label = label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 5_400_000L)));

        assertThat(label).isEqualTo(new FileLabel(null, 4_800_000L, 0L, false,
            List.of(new Span(4_805_000, 5_395_000)), List.of(new Span(WINDOW_START, 4_795_000)),
            List.of(boundary(4_795_000, 4_805_000), boundary(5_395_000, 5_405_000), after(5_405_000, D))));
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void severalMarkersInAnyOrderLeaveAMidCreditsGapLabelledFilm() {
        FileLabel label = label(row(D, 4_800_000),
            match(D, D, credits(5_100_000L, 5_300_000L), credits(4_800_000L, 4_900_000L)));

        assertThat(label.targetStartMs()).isEqualTo(4_800_000L);
        assertThat(label.credits()).containsExactly(new Span(4_805_000, 4_895_000), new Span(5_105_000, 5_295_000));
        assertThat(label.film()).containsExactly(new Span(WINDOW_START, 4_795_000), new Span(4_905_000, 5_095_000));
        assertThat(label.leftOut()).containsExactly(boundary(4_795_000, 4_805_000), boundary(4_895_000, 4_905_000),
            boundary(5_095_000, 5_105_000), boundary(5_295_000, 5_305_000), after(5_305_000, D));
        assertThat(label.leadingIsland()).isFalse();
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void overlappingNestedAndTouchingMarkersMergeAndTheBoundariesAreTheMergedOnes() {
        FileLabel label = label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 5_000_000L),
            credits(4_900_000L, 4_950_000L), credits(4_990_000L, 5_200_000L), credits(5_200_000L, 5_300_000L)));

        assertThat(label.credits()).containsExactly(new Span(4_805_000, 5_295_000));
        assertThat(label.film()).containsExactly(new Span(WINDOW_START, 4_795_000));
        assertThat(label.leftOut()).containsExactly(boundary(4_795_000, 4_805_000), boundary(5_295_000, 5_305_000),
            after(5_305_000, D));
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void mergeJoinsTouchingAndOverlappingIntervalsButNotSeparateOnes() {
        List<ArchiveItem.Marker> markers = List.of(credits(300L, 400L), credits(0L, 100L), credits(100L, 150L),
            credits(120L, 130L), credits(151L, 200L));

        assertThat(CreditsLabels.merge(markers, 10)).containsExactly(new Span(10, 160), new Span(161, 210),
            new Span(310, 410));
    }

    @Test
    void theFinalFlagAndHowManyFinalsThereAreChangeNothing() {
        ArchiveItem.Marker plain = credits(4_800_000L, 5_400_000L);
        ArchiveItem.Marker finalOne = new ArchiveItem.Marker("credits", 4_800_000L, 5_400_000L, true);
        ArchiveItem.Marker finalTwo = new ArchiveItem.Marker("credits", 5_500_000L, 5_600_000L, true);
        ArchiveItem.Marker plainTwo = credits(5_500_000L, 5_600_000L);

        assertThat(label(row(D, 4_800_000), match(D, D, plain, plainTwo)))
            .isEqualTo(label(row(D, 4_800_000), match(D, D, finalOne, finalTwo)))
            .isEqualTo(label(row(D, 4_800_000), match(D, D, finalOne, plainTwo)));
    }

    @Test
    void leadingIslandNeedsAFirstIntervalUnderSixtySecondsAndAGapOverSixtySeconds() {
        // first interval 59,999 ms, gap 60,001 ms: both on the right side of the limit
        assertThat(island(59_999, 60_001)).isTrue();
        // first interval exactly 60,000 ms is not shorter than 60 s
        assertThat(island(60_000, 60_001)).isFalse();
        // gap exactly 60,000 ms is not longer than 60 s
        assertThat(island(59_999, 60_000)).isFalse();
    }

    @Test
    void aSingleIntervalIsNeverAnIsland() {
        assertThat(label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 4_810_000L))).leadingIsland()).isFalse();
    }

    private static boolean island(long firstLength, long gap) {
        long firstEnd = 4_800_000 + firstLength;
        return label(row(D, 4_800_000), match(D, D, credits(4_800_000L, firstEnd),
            credits(firstEnd + gap, firstEnd + gap + 100_000))).leadingIsland();
    }

    @Test
    void anIslandIsLabelledLikeAnyOtherMidCreditsGap() {
        FileLabel label = label(row(D, 4_800_000),
            match(D, D, credits(4_800_000L, 4_820_000L), credits(4_900_000L, 5_000_000L)));

        assertThat(label.leadingIsland()).isTrue();
        assertThat(label.credits()).containsExactly(new Span(4_805_000, 4_815_000), new Span(4_905_000, 4_995_000));
        assertThat(label.film()).containsExactly(new Span(WINDOW_START, 4_795_000), new Span(4_825_000, 4_895_000));
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void aMarkerTypeOtherThanCreditsIsIgnoredEvenWithoutAnEnd() {
        ArchiveItem.Marker intro = new ArchiveItem.Marker("intro", 100L, null, false);
        ArchiveItem.Marker commercial = new ArchiveItem.Marker("commercial", 4_000_000L, 4_100_000L, true);

        FileLabel label = label(row(D, 4_800_000), match(D, D, intro, commercial, credits(4_800_000L, 5_400_000L)));

        assertThat(label.excluded()).isNull();
        assertThat(label.targetStartMs()).isEqualTo(4_800_000L);
        assertThat(label.credits()).containsExactly(new Span(4_805_000, 5_395_000));
    }

    @Test
    void anItemWithNoCreditsMarkerIsExcluded() {
        ArchiveItem.Marker intro = new ArchiveItem.Marker("intro", 100L, 200L, false);

        assertThat(label(row(D, 4_800_000), match(D, D, intro))).isEqualTo(FileLabel.excluded(
            ExcludeReason.NO_CREDITS_MARKER));
        assertThat(label(row(D, 4_800_000), match(D, D)))
            .isEqualTo(FileLabel.excluded(ExcludeReason.NO_CREDITS_MARKER));
    }

    @Test
    void aCreditsMarkerWithNoStartIsIgnoredAsTheImportIgnoresIt() {
        ArchiveItem.Marker noStart = new ArchiveItem.Marker("credits", null, 5_000_000L, false);

        assertThat(label(row(D, 4_800_000), match(D, D, noStart)).excluded()).isEqualTo(
            ExcludeReason.NO_CREDITS_MARKER);
        assertThat(label(row(D, 4_800_000), match(D, D, noStart, credits(4_800_000L, 5_400_000L))).excluded())
            .isNull();
    }

    @Test
    void aCreditsMarkerWithNoEndExcludesTheFile() {
        assertThat(label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 5_400_000L), credits(5_500_000L, null))))
            .isEqualTo(FileLabel.excluded(ExcludeReason.MISSING_MARKER_END));
    }

    @Test
    void theShiftUsesTheItemsOwnDurationWhenItIsTheShortest() {
        ArchiveItem.Media matched = new ArchiveItem.Media(6_002_000L, List.of("/p/A.m4v"));
        ArchiveItem.Media other = new ArchiveItem.Media(6_001_000L, List.of("/p/B.mkv"));
        ArchiveItem item = new ArchiveItem(1, 6_000_000L, List.of(matched, other),
            List.of(credits(4_800_000L, 5_400_000L)));

        assertThat(CreditsLabels.shift(new ArchiveMatch(item, matched))).isEqualTo(2_000L);
        assertThat(CreditsLabels.shift(new ArchiveMatch(item, other))).isEqualTo(1_000L);
        FileLabel label = label(row(6_002_000, 4_802_000), new ArchiveMatch(item, matched));
        assertThat(label.shiftMs()).isEqualTo(2_000L);
        assertThat(label.targetStartMs()).isEqualTo(4_802_000L);
        assertThat(label.credits()).containsExactly(new Span(4_807_000, 5_397_000));
        assertThat(label.film()).containsExactly(new Span(3_902_000, 4_797_000));
        assertThat(label.leftOut()).last().isEqualTo(after(5_407_000, 6_002_000));
        assertCovers(label, 3_902_000, 6_002_000);
    }

    @Test
    void theShiftUsesTheShortestVersionWhenItIsShorterThanTheItem() {
        ArchiveItem.Media matched = new ArchiveItem.Media(6_003_000L, List.of("/p/A.m4v"));
        ArchiveItem.Media shortest = new ArchiveItem.Media(5_999_000L, List.of("/p/B.mkv"));
        ArchiveItem item = new ArchiveItem(1, 6_001_000L, List.of(matched, shortest), List.of());

        assertThat(CreditsLabels.shift(new ArchiveMatch(item, matched))).isEqualTo(4_000L);
    }

    @Test
    void theShiftIsNeverNegative() {
        ArchiveItem.Media matched = new ArchiveItem.Media(5_999_000L, List.of("/p/A.m4v"));
        ArchiveItem item = new ArchiveItem(1, 6_000_000L, List.of(matched), List.of());

        assertThat(CreditsLabels.shift(new ArchiveMatch(item, matched))).isZero();
    }

    @Test
    void theShiftCannotBeComputedWithoutDurations() {
        ArchiveItem.Media known = new ArchiveItem.Media(6_000_000L, List.of("/p/A.m4v"));
        ArchiveItem.Media unknown = new ArchiveItem.Media(null, List.of("/p/B.mkv"));

        assertThat(CreditsLabels.shift(new ArchiveMatch(new ArchiveItem(1, null, List.of(known), List.of()), known)))
            .isNull();
        assertThat(CreditsLabels.shift(new ArchiveMatch(new ArchiveItem(1, 6_000_000L, List.of(known, unknown),
            List.of()), known))).isNull();
        assertThat(label(row(D, 4_800_000), new ArchiveMatch(new ArchiveItem(1, null, List.of(known),
            List.of(credits(4_800_000L, 5_400_000L))), known))).isEqualTo(FileLabel.excluded(
            ExcludeReason.SHIFT_MISMATCH));
    }

    @Test
    void theShiftMayDifferFromTheStoredOneByOneMillisecondButNotTwo() {
        ArchiveMatch plain = match(D, D, credits(4_800_000L, 5_400_000L));

        assertThat(label(row(D, 4_800_001), plain).excluded()).isNull();
        assertThat(label(row(D, 4_799_999), plain).excluded()).isNull();
        assertThat(label(row(D, 4_800_002), plain).excluded()).isEqualTo(ExcludeReason.SHIFT_MISMATCH);
        assertThat(label(row(D, 4_799_998), plain).excluded()).isEqualTo(ExcludeReason.SHIFT_MISMATCH);
        assertThat(label(row(D, 4_800_500), plain)).isEqualTo(FileLabel.excluded(ExcludeReason.SHIFT_MISMATCH));
    }

    @Test
    void theShiftMayBeFourSecondsButNotMore() {
        assertThat(label(row(6_004_000, 4_804_000), match(6_000_000, 6_004_000, credits(4_800_000L, 5_400_000L)))
            .shiftMs()).isEqualTo(4_000L);
        assertThat(label(row(6_004_001, 4_804_001), match(6_000_000, 6_004_001, credits(4_800_000L, 5_400_000L)))
            .excluded()).isEqualTo(ExcludeReason.SHIFT_OUT_OF_RANGE);
    }

    @Test
    void aFileWhoseFirstCreditsStartBeforeTheWindowIsExcludedButOneAtItsStartIsNot() {
        assertThat(label(row(D, 3_899_999), match(D, D, credits(3_899_999L, 4_000_000L))).excluded())
            .isEqualTo(ExcludeReason.MARKER_BEFORE_WINDOW);

        FileLabel atStart = label(row(D, WINDOW_START), match(D, D, credits(WINDOW_START, 4_000_000L)));

        assertThat(atStart.excluded()).isNull();
        assertThat(atStart.film()).isEmpty();
        assertThat(atStart.leftOut()).containsExactly(boundary(WINDOW_START, 3_905_000), boundary(3_995_000, 4_005_000),
            after(4_005_000, D));
        assertThat(atStart.credits()).containsExactly(new Span(3_905_000, 3_995_000));
        assertCovers(atStart, WINDOW_START, D);
    }

    @Test
    void theWindowIsFortyPercentOfAShortFile() {
        // 3,000,000 ms: 40% is 1,200,000, under the 35 minute cap, so the window starts at 1,800,000
        FileLabel label = label(row(3_000_000, 2_500_000),
            match(3_000_000, 3_000_000, credits(2_500_000L, 2_900_000L)));

        assertThat(label.film()).containsExactly(new Span(1_800_000, 2_495_000));
        assertCovers(label, 1_800_000, 3_000_000);
        assertThat(label(row(3_000_000, 1_799_999), match(3_000_000, 3_000_000, credits(1_799_999L, 2_900_000L)))
            .excluded()).isEqualTo(ExcludeReason.MARKER_BEFORE_WINDOW);
    }

    @Test
    void aCreditsIntervalUnderTenSecondsIsSwallowedByItsBoundaries() {
        FileLabel label = label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 4_805_300L)));

        assertThat(label.excluded()).isNull();
        assertThat(label.targetStartMs()).isEqualTo(4_800_000L);
        assertThat(label.credits()).isEmpty();
        assertThat(label.film()).containsExactly(new Span(WINDOW_START, 4_795_000));
        assertThat(label.leftOut()).containsExactly(boundary(4_795_000, 4_810_300), after(4_810_300, D));
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void aLastEndPastTheEndOfTheFileIsClamped() {
        FileLabel label = label(row(D, 5_900_000), match(D, D, credits(5_900_000L, 6_500_000L)));

        assertThat(label.film()).containsExactly(new Span(WINDOW_START, 5_895_000));
        assertThat(label.credits()).containsExactly(new Span(5_905_000, D));
        assertThat(label.leftOut()).containsExactly(boundary(5_895_000, 5_905_000));
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void boundaryTakesPrecedenceOverAfterLastCredits() {
        // the last credits end at 5,400,000; the 5 s either side is BOUNDARY and only then AFTER_LAST_CREDITS starts
        FileLabel label = label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 5_400_000L)));

        assertThat(label.leftOut()).extracting(LeftOut::reason).containsExactly(LeftOutReason.BOUNDARY,
            LeftOutReason.BOUNDARY, LeftOutReason.AFTER_LAST_CREDITS);
        assertThat(label.leftOut().get(2).fromMs()).isEqualTo(5_405_000);
    }

    @Test
    void aSecondCreditsIntervalFarAfterTheFirstKeepsAfterLastCreditsOnlyAfterTheLast() {
        FileLabel label = label(row(D, 4_800_000), match(D, D, credits(4_800_000L, 4_900_000L),
            credits(5_800_000L, 5_900_000L)));

        assertThat(label.film()).containsExactly(new Span(WINDOW_START, 4_795_000), new Span(4_905_000, 5_795_000));
        assertThat(label.leftOut()).last().isEqualTo(after(5_905_000, D));
        assertCovers(label, WINDOW_START, D);
    }

    @Test
    void exclusionReasonsComeInTheClosedSetOrder() {
        ExportRow notLast = new ExportRow(1, "A.m4v", "1:1", D, 1, false, 1, 1L, "A", 2000, null, null, 4_800_000,
            "2:2", "MISSING");
        assertThat(CreditsLabels.label(notLast, List.of(), true).excluded()).isEqualTo(ExcludeReason.NOT_LAST_PART);

        ExportRow notPresent = new ExportRow(1, "A.m4v", "1:1", D, 1, true, 1, 1L, "A", 2000, null, null, 4_800_000,
            "2:2", "MISSING");
        assertThat(CreditsLabels.label(notPresent, List.of(), true).excluded()).isEqualTo(ExcludeReason.NOT_PRESENT);

        ExportRow changed = new ExportRow(1, "A.m4v", "1:1", D, 1, true, 1, 1L, "A", 2000, null, null, 4_800_000,
            "2:2", "PRESENT");
        assertThat(CreditsLabels.label(changed, List.of(), true).excluded())
            .isEqualTo(ExcludeReason.FINGERPRINT_CHANGED);

        assertThat(CreditsLabels.label(row(D, 4_800_000), List.of(), true).excluded())
            .isEqualTo(ExcludeReason.NO_ARCHIVE_ITEM);

        ArchiveMatch ok = match(D, D, credits(4_800_000L, 5_400_000L));
        assertThat(CreditsLabels.label(row(D, 4_800_000), List.of(ok, ok), true).excluded())
            .isEqualTo(ExcludeReason.AMBIGUOUS_ARCHIVE_ITEM);
    }

    @Test
    void theOwnerExclusionComesAfterEveryDataReasonAndBeforeLabelling() {
        ArchiveMatch ok = match(D, D, credits(4_800_000L, 5_400_000L));
        assertThat(CreditsLabels.label(row(D, 4_800_000), List.of(ok), true))
            .isEqualTo(FileLabel.excluded(ExcludeReason.OWNER_EXCLUDED));
        assertThat(CreditsLabels.label(row(D, 4_800_000), List.of(ok), false).excluded()).isNull();

        ArchiveMatch early = match(D, D, credits(3_000_000L, 3_100_000L));
        assertThat(CreditsLabels.label(row(D, 3_000_000), List.of(early), true).excluded())
            .isEqualTo(ExcludeReason.MARKER_BEFORE_WINDOW);
    }

    @Test
    void aNullLastPartFlagCountsAsNotTheLastPart() {
        ExportRow nonOriginal = new ExportRow(1, "A.m4v", "10:20", D, 1, null, 1, 1L, "A", 2000, null, null, 4_800_000,
            "10:20", "PRESENT");

        assertThat(CreditsLabels.label(nonOriginal, List.of(), false).excluded())
            .isEqualTo(ExcludeReason.NOT_LAST_PART);
    }

    @Test
    void aMissingOrDifferentFingerprintIsAChange() {
        ExportRow noFingerprint = new ExportRow(1, "A.m4v", null, D, 1, true, 1, 1L, "A", 2000, null, null, 4_800_000,
            null, "PRESENT");

        assertThat(CreditsLabels.label(noFingerprint, List.of(), false).excluded())
            .isEqualTo(ExcludeReason.FINGERPRINT_CHANGED);
    }

    @Test
    void anExcludedLabelHasNullFiguresAndEmptyLists() {
        assertThat(FileLabel.excluded(ExcludeReason.NO_ARCHIVE_ITEM)).isEqualTo(new FileLabel(
            ExcludeReason.NO_ARCHIVE_ITEM, null, null, null, List.of(), List.of(), List.of()));
    }
}
