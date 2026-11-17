package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A subtitle file is proven by any one of: the owner's check, the OpenSubtitles comparison, the timing check. */
class SidecarTrustTest {

    private static final Instant AT = Instant.parse("2024-04-01T01:30:00Z");

    private static CheckDto check(String verdict) {
        return new CheckDto(verdict, List.of(), 0, 1.0, 12, 12, "Taken.2008", AT);
    }

    private static AlignmentDto alignment(String verdict) {
        return new AlignmentDto(verdict, List.of(), 0, 1.0, 12, 12, false, AT, "EMBEDDED", null);
    }

    @Test
    void theOwnersCheckProvesItWithNoAutomaticCheck() {
        assertThat(proven(true, null, null)).isTrue();
    }

    @Test
    void anAgreeingOpenSubtitlesCheckProvesIt() {
        assertThat(proven(false, check("AGREES"), null)).isTrue();
        assertThat(proven(false, check("AGREES"), alignment("CHECK"))).isTrue();
    }

    @Test
    void anAlignedTimingCheckProvesIt() {
        assertThat(proven(false, null, alignment("ALIGNED"))).isTrue();
        assertThat(proven(false, check("CHECK"), alignment("ALIGNED"))).isTrue();
    }

    @Test
    void nothingElseProvesIt() {
        assertThat(proven(false, null, null)).isFalse();
        assertThat(proven(false, check("CHECK"), null)).isFalse();
        assertThat(proven(false, null, alignment("CHECK"))).isFalse();
        assertThat(proven(false, null, alignment("TOO_FEW"))).isFalse();
        assertThat(proven(false, null, alignment("NO_REFERENCE"))).isFalse();
        assertThat(proven(false, check("CHECK"), alignment("CHECK"))).isFalse();
    }

    @Test
    void aPickedDownloadIsNeverFailedBecauseTheAssessmentSaysSo() {
        assertThat(SidecarTrust.assess(true, false, "CHECK", false))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.CHOSEN));
        assertThat(SidecarTrust.assess(true, true, "AGREES", true))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.CHOSEN));
        assertThat(SidecarTrust.assess(false, false, "CHECK", false))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.FAILED, SidecarTrust.Proof.NONE));
        assertThat(SidecarTrust.assess(false, true, "CHECK", false))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.VERIFIED));
    }

    @Test
    void assessingSaysProvenFailedOrUncheckedAndWhatProvedIt() {
        assertThat(SidecarTrust.assess(true, "CHECK", false))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.VERIFIED));
        assertThat(SidecarTrust.assess(false, "AGREES", true))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.AGREES));
        assertThat(SidecarTrust.assess(false, "CHECK", true))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.ALIGNED));
        assertThat(SidecarTrust.assess(false, null, true))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.PROVEN, SidecarTrust.Proof.ALIGNED));
        assertThat(SidecarTrust.assess(false, "CHECK", false))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.FAILED, SidecarTrust.Proof.NONE));
        assertThat(SidecarTrust.assess(false, null, false))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.UNCHECKED, SidecarTrust.Proof.NONE));
    }

    @Test
    void aTimingFitThatCouldNotProveAnythingLeavesTheFileUncheckedNotFailed() {
        // 3a not ALIGNED alone is not a failure: only a CHECK from OpenSubtitles fails a file.
        assertThat(SidecarTrust.assess(false, null, false).trust()).isEqualTo(SidecarTrust.Trust.UNCHECKED);
    }

    @Test
    void alignedMeansACurrentAlignedFitOnly() {
        assertThat(SidecarTrust.isAligned(null)).isFalse();
        assertThat(SidecarTrust.isAligned(alignment("CHECK"))).isFalse();
        assertThat(SidecarTrust.isAligned(alignment("ALIGNED"))).isTrue();
    }

    private static AlignmentDto openSubtitlesAligned() {
        return new AlignmentDto("ALIGNED", List.of(), 0, 1.0, 12, 12, false, AT, "OPENSUBTITLES", "Taken.2008");
    }

    @Test
    void aFitToAnOpenSubtitlesFileNeverCountsAsAlignedWhateverItsVerdict() {
        assertThat(SidecarTrust.isAligned(openSubtitlesAligned())).isFalse();
    }

    @Test
    void anOpenSubtitlesFitLeavesAFailedComparisonFailedAndAnUncheckedFileUnproven() {
        boolean aligned = SidecarTrust.isAligned(openSubtitlesAligned());

        assertThat(SidecarTrust.assess(false, "CHECK", aligned))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.FAILED, SidecarTrust.Proof.NONE));
        assertThat(SidecarTrust.assess(false, null, aligned))
            .isEqualTo(new SidecarTrust.Assessment(SidecarTrust.Trust.UNCHECKED, SidecarTrust.Proof.NONE));
    }

    @Test
    void anOpenSubtitlesFitDoesNotMakeADownloadBeatTheEmbeddedForcedTrackOrChangeAnyStepOfTheChoice() {
        SidecarTrust.Assessment withFit = SidecarTrust.assess(false, false, null,
            SidecarTrust.isAligned(openSubtitlesAligned()));
        SidecarTrust.Assessment without = SidecarTrust.assess(false, false, null, false);
        List<ForcedChoice.DownloadSide> downloads = List.of(new ForcedChoice.DownloadSide(7L, withFit));
        ForcedChoice.FileSide failedFile = new ForcedChoice.FileSide(
            new SidecarTrust.Assessment(SidecarTrust.Trust.FAILED, SidecarTrust.Proof.NONE), 12);

        assertThat(withFit).isEqualTo(without);
        assertThat(ForcedChoice.decide(new ForcedChoice.Input(null, downloads, true)).step())
            .isEqualTo(ForcedChoice.Step.EMBEDDED);
        assertThat(ForcedChoice.decide(new ForcedChoice.Input(null, downloads, false)).step())
            .isEqualTo(ForcedChoice.Step.UNCHECKED_DOWNLOAD);
        assertThat(ForcedChoice.decide(new ForcedChoice.Input(failedFile, downloads, false)).step())
            .isEqualTo(ForcedChoice.Step.REPLACED_BY_DOWNLOAD);
    }

    @Test
    void anOwnerFileThatFailedItsComparisonStaysFailedKeptOrDroppedWhateverAnOpenSubtitlesFitSays() {
        boolean aligned = SidecarTrust.isAligned(openSubtitlesAligned());
        SidecarTrust.Assessment failed = SidecarTrust.assess(false, "CHECK", aligned);

        assertThat(failed.trust()).isEqualTo(SidecarTrust.Trust.FAILED);
        assertThat(SidecarTrust.assess(false, false, "CHECK", aligned).trust()).isEqualTo(SidecarTrust.Trust.FAILED);
        assertThat(ForcedChoice.decide(new ForcedChoice.Input(new ForcedChoice.FileSide(failed, 12), List.of(), false))
            .step()).isEqualTo(ForcedChoice.Step.FAILED_FILE_KEPT);
        assertThat(ForcedChoice.decide(new ForcedChoice.Input(new ForcedChoice.FileSide(failed, 300), List.of(),
            false)).step()).isEqualTo(ForcedChoice.Step.FAILED_FILE_DROPPED);
    }

    /** Whether any one proof holds, through the production {@code assess} the resolver uses. */
    private static boolean proven(boolean ownerVerified, CheckDto check, AlignmentDto alignment) {
        return SidecarTrust.assess(ownerVerified, check == null ? null : check.verdict(),
            SidecarTrust.isAligned(alignment)).trust() == SidecarTrust.Trust.PROVEN;
    }
}
