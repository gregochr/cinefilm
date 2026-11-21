package gregochr.cinefilm.credits;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.credits.CreditsApply.Applied;
import gregochr.cinefilm.credits.CreditsApply.Part;
import gregochr.cinefilm.credits.CreditsApply.PlexRow;
import gregochr.cinefilm.credits.CreditsApply.PlexState;
import gregochr.cinefilm.credits.CreditsApply.PlexStatus;
import gregochr.cinefilm.film.CreditsMarkerSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The marker an edition should have: step 1 owner, step 2 Plex, step 4 cleared. */
class CreditsApplyTest {

    private static final String FILE = "4000000000:1700000000000";
    /** One part of 6,000,000 ms: 60% is 3,600,000; 60 s from the end is 5,940,000. */
    private static final List<Part> ONE_PART = List.of(new Part(1, 6_000_000L));
    /** Parts of 4,000,000 and 2,000,000 ms: 6,000,000 in all; the same limits. */
    private static final List<Part> TWO_PARTS = List.of(new Part(1, 4_000_000L), new Part(2, 2_000_000L));
    private static final Applied NONE = new Applied(null, null);

    private static Applied plex(List<Part> parts, long startMs) {
        return CreditsApply.apply(parts, FILE, null, null, new PlexRow(FILE, startMs));
    }

    @Test
    void aMatchingPlexRowInsideTheLimitsSetsPlexMarker() {
        assertThat(plex(ONE_PART, 5_000_000)).isEqualTo(new Applied(5_000_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void aPlexRowReplacesAnAutoMarkerAndAMarkerWithNoSource() {
        PlexRow row = new PlexRow(FILE, 5_000_000);

        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.AUTO, row))
            .isEqualTo(new Applied(5_000_000L, CreditsMarkerSource.PLEX));
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, null, row))
            .isEqualTo(new Applied(5_000_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void anOwnerMarkerIsKeptEvenWhenPlexHasAMatchingRow() {
        Applied applied = CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.OWNER,
            new PlexRow(FILE, 5_000_000));

        assertThat(applied).isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.OWNER));
    }

    @Test
    void anOwnerMarkerIsKeptWithNoFingerprintAndNoDurations() {
        Applied applied = CreditsApply.apply(List.of(new Part(1, null)), null, 5_500_000L,
            CreditsMarkerSource.OWNER, null);

        assertThat(applied).isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.OWNER));
    }

    @Test
    void aRowWhoseFingerprintNoLongerMatchesTheFileClearsTheMarker() {
        Applied applied = CreditsApply.apply(ONE_PART, "4000000001:1700000000000", 5_500_000L,
            CreditsMarkerSource.PLEX, new PlexRow(FILE, 5_000_000));

        assertThat(applied).isEqualTo(NONE);
    }

    @Test
    void noPlexRowClearsAnExistingMarker() {
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.AUTO, null)).isEqualTo(NONE);
    }

    @Test
    void sixtyPercentIntoTheEditionIsTheEarliestPlexMarkerAllowed() {
        assertThat(plex(ONE_PART, 3_600_000)).isEqualTo(new Applied(3_600_000L, CreditsMarkerSource.PLEX));
        assertThat(plex(ONE_PART, 3_599_999)).isEqualTo(NONE);
        assertThat(plex(ONE_PART, 3_600_001)).isEqualTo(new Applied(3_600_001L, CreditsMarkerSource.PLEX));
    }

    @Test
    void sixtySecondsBeforeTheEndIsTheLatestPlexMarkerAllowed() {
        assertThat(plex(ONE_PART, 5_940_000)).isEqualTo(new Applied(5_940_000L, CreditsMarkerSource.PLEX));
        assertThat(plex(ONE_PART, 5_939_999)).isEqualTo(new Applied(5_939_999L, CreditsMarkerSource.PLEX));
        assertThat(plex(ONE_PART, 5_940_001)).isEqualTo(NONE);
    }

    @Test
    void anOffsetOutsideTheLastPartGivesNoMarker() {
        assertThat(plex(ONE_PART, 6_000_001)).isEqualTo(NONE);
        assertThat(plex(ONE_PART, -1)).isEqualTo(NONE);
    }

    @Test
    void aTwoPartEditionsMarkerLandsAtPartTwosEditionTime() {
        assertThat(plex(TWO_PARTS, 1_500_000)).isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.PLEX));
    }

    @Test
    void noPartsOrAPartWithoutADurationMeansNoTimelineAndNoMarker() {
        assertThat(plex(List.of(), 5_000_000)).isEqualTo(NONE);
        assertThat(plex(List.of(new Part(1, null)), 5_000_000)).isEqualTo(NONE);
        assertThat(plex(List.of(new Part(1, 0L)), 5_000_000)).isEqualTo(NONE);
        assertThat(plex(List.of(new Part(1, 4_000_000L), new Part(2, null)), 1_500_000)).isEqualTo(NONE);
    }

    @Test
    void aLastPartWithNoFingerprintGivesNoMarker() {
        Applied applied = CreditsApply.apply(ONE_PART, null, 5_500_000L, CreditsMarkerSource.PLEX,
            new PlexRow(FILE, 5_000_000));

        assertThat(applied).isEqualTo(NONE);
    }

    @Test
    void anEditionWithNoMarkerAndNoRowStaysWithNone() {
        assertThat(CreditsApply.apply(ONE_PART, FILE, null, null, null)).isEqualTo(NONE);
    }

    private static PlexStatus status(List<Part> parts, String fingerprint, PlexRow row) {
        return CreditsApply.plexStatus(parts, fingerprint, row);
    }

    @Test
    void noRowIsNoneWhateverElseIsWrong() {
        assertThat(status(List.of(), null, null)).isEqualTo(new PlexStatus(PlexState.NONE, null));
        assertThat(status(ONE_PART, FILE, null)).isEqualTo(new PlexStatus(PlexState.NONE, null));
    }

    @Test
    void aRowWithNoFingerprintOnTheFileIsNoFingerprintBeforeAnyLaterCheck() {
        assertThat(status(List.of(new Part(1, null)), null, new PlexRow(FILE, 5_000_000)))
            .isEqualTo(new PlexStatus(PlexState.NO_FINGERPRINT, null));
    }

    @Test
    void aRowForAnOlderCopyIsStaleEvenWhenThePartHasNoDuration() {
        assertThat(status(List.of(new Part(1, null)), "1:2", new PlexRow(FILE, 5_000_000)))
            .isEqualTo(new PlexStatus(PlexState.STALE, null));
    }

    @Test
    void noPartsOrAPartWithoutADurationIsNoTimelineBeforeTheOffsetIsLookedAt() {
        PlexRow row = new PlexRow(FILE, 99_000_000);

        assertThat(status(List.of(), FILE, row)).isEqualTo(new PlexStatus(PlexState.NO_TIMELINE, null));
        assertThat(status(List.of(new Part(1, null)), FILE, row))
            .isEqualTo(new PlexStatus(PlexState.NO_TIMELINE, null));
    }

    @Test
    void anOffsetOutsideTheLastPartIsOutsideBeforeTheGuardIsApplied() {
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 6_000_001)))
            .isEqualTo(new PlexStatus(PlexState.OUTSIDE, null));
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, -1)))
            .isEqualTo(new PlexStatus(PlexState.OUTSIDE, null));
    }

    @Test
    void aTimeInsideThePartButOutsideTheGuardIsRefusedAndCarriesPlexsTime() {
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_599_999)))
            .isEqualTo(new PlexStatus(PlexState.REFUSED, 3_599_999L));
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_940_001)))
            .isEqualTo(new PlexStatus(PlexState.REFUSED, 5_940_001L));
    }

    @Test
    void theGuardBoundsAreUsableAtAndInsideAndRefusedOneMillisecondOutside() {
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_600_000)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_599_999)).state()).isEqualTo(PlexState.REFUSED);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 3_600_001)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_940_000)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_939_999)).state()).isEqualTo(PlexState.USABLE);
        assertThat(status(ONE_PART, FILE, new PlexRow(FILE, 5_940_001)).state()).isEqualTo(PlexState.REFUSED);
    }

    @Test
    void aUsableRowCarriesItsTimeInEditionTime() {
        assertThat(status(TWO_PARTS, FILE, new PlexRow(FILE, 1_500_000)))
            .isEqualTo(new PlexStatus(PlexState.USABLE, 5_500_000L));
    }

    @Test
    void theStatusIsTheSameWhateverTheEditionsSourceIs() {
        PlexRow row = new PlexRow(FILE, 5_000_000);
        PlexStatus expected = new PlexStatus(PlexState.USABLE, 5_000_000L);

        assertThat(status(ONE_PART, FILE, row)).isEqualTo(expected);
        assertThat(CreditsApply.apply(ONE_PART, FILE, 5_500_000L, CreditsMarkerSource.OWNER, row))
            .isEqualTo(new Applied(5_500_000L, CreditsMarkerSource.OWNER));
    }
}
