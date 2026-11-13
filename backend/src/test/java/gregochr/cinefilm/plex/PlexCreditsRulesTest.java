package gregochr.cinefilm.plex;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.plex.PlexCreditsRules.Copy;
import gregochr.cinefilm.plex.PlexCreditsRules.Decision;
import gregochr.cinefilm.plex.PlexCreditsRules.Row;
import gregochr.cinefilm.plex.PlexModels.PlexItemMarkers;
import gregochr.cinefilm.plex.PlexModels.PlexMarker;
import gregochr.cinefilm.plex.PlexModels.PlexMediaTiming;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which Plex markers are stored, and where they land in each copy: the pure rules, no collaborators. */
class PlexCreditsRulesTest {

    private static final String PREFIX = "/m/";
    private static final long HOUR2 = 7_200_000L;
    /** 91.7% into a two-hour film. */
    private static final long MARKER = 6_600_000L;

    private static PlexMediaTiming media(Long durationMs, String... names) {
        return new PlexMediaTiming(durationMs, java.util.Arrays.stream(names).map(n -> PREFIX + n).toList());
    }

    private static PlexMarker credits(long startMs) {
        return new PlexMarker("credits", startMs, true);
    }

    private static PlexItemMarkers item(Long durationMs, List<PlexMediaTiming> media, PlexMarker... markers) {
        return new PlexItemMarkers(durationMs, media, List.of(markers));
    }

    private static Copy copy(long id, String path, Long durationMs) {
        return new Copy(id, path, "fp" + id, durationMs);
    }

    private static Decision decide(PlexItemMarkers item, Copy... copies) {
        return PlexCreditsRules.decide(item, List.of(copies), PREFIX);
    }

    @Test
    void aSingleCopyGetsPlexsMarkerAsItIs() {
        Decision decision = decide(item(HOUR2, List.of(media(HOUR2, "a.m4v")), credits(MARKER)),
            copy(1, "a.m4v", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER));
        assertThat(decision.skipped()).isEmpty();
        assertThat(decision.unmatched()).isZero();
    }

    @Test
    void theEarliestCreditsMarkerWinsWhateverTheListOrder() {
        Decision decision = decide(item(HOUR2, List.of(media(HOUR2, "a.m4v")), credits(6_900_000L),
            credits(MARKER)), copy(1, "a.m4v", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER));
    }

    @Test
    void otherMarkerTypesAreIgnoredAndANonFinalCreditsMarkerIsAccepted() {
        Decision decision = decide(item(HOUR2, List.of(media(HOUR2, "a.m4v")),
            new PlexMarker("intro", 1_000L, false), new PlexMarker("credits", MARKER, false)),
            copy(1, "a.m4v", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER));
    }

    @Test
    void anItemWithNoCreditsMarkerIsSkippedOnceEvenWithAnIntroMarker() {
        Decision decision = decide(item(HOUR2, List.of(media(HOUR2, "a.m4v")), new PlexMarker("intro", 1_000L, false)),
            copy(1, "a.m4v", HOUR2));

        assertThat(decision.rows()).isEmpty();
        assertThat(decision.skipped()).isEqualTo(Map.of(PlexCreditsSkip.NO_CREDITS_MARKER, 1));
    }

    @Test
    void aMarkerUnderSixtyPercentIntoTheItemIsSkippedAndExactlySixtyPercentIsKept() {
        PlexMediaTiming only = media(HOUR2, "a.m4v");

        assertThat(decide(item(HOUR2, List.of(only), credits(4_319_999L)), copy(1, "a.m4v", HOUR2)).skipped())
            .isEqualTo(Map.of(PlexCreditsSkip.MARKER_IMPLAUSIBLE, 1));
        assertThat(decide(item(HOUR2, List.of(only), credits(4_320_000L)), copy(1, "a.m4v", HOUR2)).rows())
            .containsExactly(new Row(1, "fp1", 4_320_000L));
    }

    @Test
    void aMarkerUnderSixtySecondsBeforeTheEndIsSkippedAndExactlySixtySecondsIsKept() {
        PlexMediaTiming only = media(HOUR2, "a.m4v");

        assertThat(decide(item(HOUR2, List.of(only), credits(7_140_001L)), copy(1, "a.m4v", HOUR2)).skipped())
            .isEqualTo(Map.of(PlexCreditsSkip.MARKER_IMPLAUSIBLE, 1));
        assertThat(decide(item(HOUR2, List.of(only), credits(7_140_000L)), copy(1, "a.m4v", HOUR2)).rows())
            .containsExactly(new Row(1, "fp1", 7_140_000L));
    }

    @Test
    void aVersionMoreThanTwoSecondsFromTheItemSkipsTheWholeItemAndTwoSecondsExactlyIsFine() {
        PlexItemMarkers far = item(HOUR2, List.of(media(HOUR2, "a.m4v"), media(HOUR2 + 2_001, "b.mp4")),
            credits(MARKER));
        Decision skipped = decide(far, copy(1, "a.m4v", HOUR2), copy(2, "b.mp4", HOUR2 + 2_001));

        assertThat(skipped.rows()).isEmpty();
        assertThat(skipped.skipped()).isEqualTo(Map.of(PlexCreditsSkip.DURATION_MISMATCH, 1));

        PlexItemMarkers near = item(HOUR2, List.of(media(HOUR2, "a.m4v"), media(HOUR2 - 2_000, "b.mp4")),
            credits(MARKER));
        assertThat(decide(near, copy(1, "a.m4v", HOUR2), copy(2, "b.mp4", HOUR2 - 2_000)).rows()).hasSize(2);
    }

    @Test
    void anItemOrAVersionWithNoPlexDurationIsSkippedWithItsOwnReason() {
        assertThat(decide(item(null, List.of(media(HOUR2, "a.m4v")), credits(MARKER)), copy(1, "a.m4v", HOUR2))
            .skipped()).isEqualTo(Map.of(PlexCreditsSkip.ITEM_NO_DURATION, 1));
        assertThat(decide(item(0L, List.of(media(HOUR2, "a.m4v")), credits(MARKER)), copy(1, "a.m4v", HOUR2))
            .skipped()).isEqualTo(Map.of(PlexCreditsSkip.ITEM_NO_DURATION, 1));
        assertThat(decide(item(HOUR2, List.of(media(null, "a.m4v")), credits(MARKER)), copy(1, "a.m4v", HOUR2))
            .skipped()).isEqualTo(Map.of(PlexCreditsSkip.MEDIA_NO_DURATION, 1));
        assertThat(decide(item(HOUR2, List.of(media(HOUR2, "a.m4v"), media(0L, "b.mp4")), credits(MARKER)),
            copy(1, "a.m4v", HOUR2)).skipped()).isEqualTo(Map.of(PlexCreditsSkip.MEDIA_NO_DURATION, 1));
    }

    @Test
    void aSplitEditionWithSeveralPartsInOneVersionIsSkipped() {
        PlexItemMarkers split = item(HOUR2, List.of(media(HOUR2, "T - pt1.m4v", "T - pt2.m4v")), credits(MARKER));

        Decision decision = decide(split, copy(1, "T - pt1.m4v", HOUR2), copy(2, "T - pt2.m4v", HOUR2));

        assertThat(decision.rows()).isEmpty();
        assertThat(decision.skipped()).isEqualTo(Map.of(PlexCreditsSkip.MULTI_PART, 1));
    }

    @Test
    void aVersionWithTwoPartsIsSplitEvenWhenOnlyOnePartHasAFile() {
        PlexMediaTiming oneFileOfTwo = new PlexMediaTiming(HOUR2, List.of(PREFIX + "T - pt1.m4v"), 2);

        Decision decision = decide(item(HOUR2, List.of(oneFileOfTwo), credits(MARKER)), copy(1, "T - pt1.m4v", HOUR2));

        assertThat(decision.rows()).isEmpty();
        assertThat(decision.skipped()).isEqualTo(Map.of(PlexCreditsSkip.MULTI_PART, 1));
    }

    @Test
    void aVersionWhoseOnlyPartHasNoFileIsNoMediaNotSplit() {
        PlexMediaTiming noFile = new PlexMediaTiming(HOUR2, List.of(), 1);

        assertThat(decide(item(HOUR2, List.of(noFile), credits(MARKER))).skipped())
            .isEqualTo(Map.of(PlexCreditsSkip.NO_MEDIA, 1));
    }

    @Test
    void anItemWithNoVersionOrAVersionWithNoFileIsSkipped() {
        assertThat(decide(item(HOUR2, List.of(), credits(MARKER))).skipped())
            .isEqualTo(Map.of(PlexCreditsSkip.NO_MEDIA, 1));
        assertThat(decide(item(HOUR2, List.of(media(HOUR2)), credits(MARKER))).skipped())
            .isEqualTo(Map.of(PlexCreditsSkip.NO_MEDIA, 1));
    }

    @Test
    void theMarkerMovesLaterByHowMuchLongerTheCopyIsThanTheShortestPlexLength() {
        long longer = HOUR2 + 1_500;
        PlexItemMarkers two = item(HOUR2, List.of(media(longer, "a.m4v"), media(HOUR2, "b.mp4")), credits(MARKER));

        Decision decision = decide(two, copy(1, "a.m4v", longer), copy(2, "b.mp4", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER + 1_500), new Row(2, "fp2", MARKER));
    }

    @Test
    void theShortestIsTheItemsOwnLengthWhenNoVersionIsShorterAndTheShiftIsNeverNegative() {
        long shorter = HOUR2 - 1_800;
        PlexItemMarkers two = item(HOUR2, List.of(media(shorter, "a.m4v"), media(HOUR2, "b.mp4")), credits(MARKER));

        Decision decision = decide(two, copy(1, "a.m4v", shorter), copy(2, "b.mp4", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER), new Row(2, "fp2", MARKER + 1_800));
    }

    @Test
    void aCopyIsMatchedToItsVersionByPathNotByPosition() {
        long longer = HOUR2 + 1_500;
        // Plex lists the longer .mp4 first; CineFilm's copies are in the other order.
        PlexItemMarkers two = item(HOUR2, List.of(media(longer, "b.mp4"), media(HOUR2, "a.m4v")), credits(MARKER));

        Decision decision = decide(two, copy(1, "a.m4v", HOUR2), copy(2, "b.mp4", longer));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER), new Row(2, "fp2", MARKER + 1_500));
    }

    @Test
    void aPathStoredDecomposedStillMatchesAPathPlexKeepsComposed() {
        PlexItemMarkers composed = item(HOUR2, List.of(media(HOUR2, "Amélie.m4v")), credits(MARKER));

        Decision decision = decide(composed, copy(1, "Amélie.m4v", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER));
    }

    @Test
    void aCopyThatMatchesNoVersionIsCountedWhileItsSiblingIsStillStored() {
        PlexItemMarkers one = item(HOUR2, List.of(media(HOUR2, "a.m4v")), credits(MARKER));

        Decision decision = decide(one, copy(1, "a.m4v", HOUR2), copy(2, "renamed.mp4", HOUR2));

        assertThat(decision.rows()).containsExactly(new Row(1, "fp1", MARKER));
        assertThat(decision.unmatched()).isEqualTo(1);
        assertThat(decision.skipped()).isEmpty();
    }

    @Test
    void aVersionOutsideThePrefixMatchesNoCopy() {
        PlexItemMarkers elsewhere = item(HOUR2, List.of(new PlexMediaTiming(HOUR2, List.of("/other/a.m4v"))),
            credits(MARKER));

        Decision decision = decide(elsewhere, copy(1, "a.m4v", HOUR2));

        assertThat(decision.rows()).isEmpty();
        assertThat(decision.unmatched()).isEqualTo(1);
    }

    @Test
    void theMovedMarkerIsGuardedAgainstTheCineFilmFilesLength() {
        long version = HOUR2 + 1_000;
        // The marker moves 1,000 ms later. A file 1,500 ms shorter than its version ends sooner than the shift allows.
        long file = version - 1_500;
        PlexItemMarkers kept = item(HOUR2, List.of(media(version, "a.m4v"), media(HOUR2, "b.mp4")),
            credits(7_139_500L));
        // Moved to 7,140,500 in a file of 7,199,500 ms: 59,000 ms from the end.
        Decision tooLate = decide(kept, copy(1, "a.m4v", file));
        assertThat(tooLate.rows()).isEmpty();
        assertThat(tooLate.skipped()).isEqualTo(Map.of(PlexCreditsSkip.SHIFTED_IMPLAUSIBLE, 1));

        // The same marker in a file as long as its version keeps exactly 60,500 ms before the end.
        assertThat(decide(kept, copy(1, "a.m4v", version)).rows()).containsExactly(new Row(1, "fp1", 7_140_500L));
    }

    @Test
    void theMovedMarkerMustAlsoBeSixtyPercentIntoTheFile() {
        // A ten-minute film with the marker at exactly 60% of Plex's length.
        long ten = 600_000L;
        PlexItemMarkers one = item(ten, List.of(media(ten, "a.m4v")), credits(360_000L));

        // The file is 2 s longer than Plex measured: 360,000 / 602,000 is under 60%.
        Decision decision = decide(one, copy(1, "a.m4v", ten + 2_000));

        assertThat(decision.rows()).isEmpty();
        assertThat(decision.skipped()).isEqualTo(Map.of(PlexCreditsSkip.SHIFTED_IMPLAUSIBLE, 1));
    }

    @Test
    void aCopyWithAnUnknownLengthIsSkippedAndItsSiblingStored() {
        PlexItemMarkers two = item(HOUR2, List.of(media(HOUR2, "a.m4v"), media(HOUR2, "b.mp4")), credits(MARKER));

        Decision decision = decide(two, copy(1, "a.m4v", null), copy(2, "b.mp4", HOUR2), copy(3, "b.mp4", 0L));

        assertThat(decision.rows()).extracting(Row::mediaFileId).containsExactly(2L);
        assertThat(decision.skipped()).isEqualTo(Map.of(PlexCreditsSkip.LOCAL_DURATION_UNKNOWN, 2));
    }

    @Test
    void aCopyMoreThanTwoSecondsFromItsPlexVersionIsSkippedAndTwoSecondsExactlyIsKept() {
        PlexItemMarkers one = item(HOUR2, List.of(media(HOUR2, "a.m4v")), credits(MARKER));

        Decision far = decide(one, copy(1, "a.m4v", HOUR2 + 2_001));
        assertThat(far.rows()).isEmpty();
        assertThat(far.skipped()).isEqualTo(Map.of(PlexCreditsSkip.LOCAL_DURATION_DIFFERS, 1));
        assertThat(decide(one, copy(1, "a.m4v", HOUR2 - 2_000)).rows()).hasSize(1);
    }

    @Test
    void aCopyWithNoFingerprintIsSkipped() {
        PlexItemMarkers one = item(HOUR2, List.of(media(HOUR2, "a.m4v")), credits(MARKER));

        Decision decision = decide(one, new Copy(1, "a.m4v", null, HOUR2));

        assertThat(decision.rows()).isEmpty();
        assertThat(decision.skipped()).isEqualTo(Map.of(PlexCreditsSkip.NO_FINGERPRINT, 1));
    }
}
