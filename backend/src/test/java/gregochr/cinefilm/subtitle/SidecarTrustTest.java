package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
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
        return new AlignmentDto(verdict, List.of(), 0, 1.0, 12, 12, false, AT);
    }

    @Test
    void theOwnersCheckProvesItWithNoAutomaticCheck() {
        assertThat(SidecarTrust.isProven(true, null, null)).isTrue();
    }

    @Test
    void anAgreeingOpenSubtitlesCheckProvesIt() {
        assertThat(SidecarTrust.isProven(false, check("AGREES"), null)).isTrue();
        assertThat(SidecarTrust.isProven(false, check("AGREES"), alignment("CHECK"))).isTrue();
    }

    @Test
    void anAlignedTimingCheckProvesIt() {
        assertThat(SidecarTrust.isProven(false, null, alignment("ALIGNED"))).isTrue();
        assertThat(SidecarTrust.isProven(false, check("CHECK"), alignment("ALIGNED"))).isTrue();
    }

    @Test
    void nothingElseProvesIt() {
        assertThat(SidecarTrust.isProven(false, null, null)).isFalse();
        assertThat(SidecarTrust.isProven(false, check("CHECK"), null)).isFalse();
        assertThat(SidecarTrust.isProven(false, null, alignment("CHECK"))).isFalse();
        assertThat(SidecarTrust.isProven(false, null, alignment("TOO_FEW"))).isFalse();
        assertThat(SidecarTrust.isProven(false, null, alignment("NO_REFERENCE"))).isFalse();
        assertThat(SidecarTrust.isProven(false, check("CHECK"), alignment("CHECK"))).isFalse();
    }

    @Test
    void aListedFileIsProvenByItsOwnVerifiedCheckAndAlignmentFields() {
        assertThat(SidecarTrust.isProven(listed(true, null, null))).isTrue();
        assertThat(SidecarTrust.isProven(listed(false, check("AGREES"), null))).isTrue();
        assertThat(SidecarTrust.isProven(listed(false, null, alignment("ALIGNED")))).isTrue();
        assertThat(SidecarTrust.isProven(listed(false, null, alignment("CHECK")))).isFalse();
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

    private static SidecarDto listed(boolean verified, CheckDto check, AlignmentDto alignment) {
        return new SidecarDto(5L, "Taken.en.forced.srt", "PRESENT", "matched", 7L, "Taken", 70L, "Original", 1, 1,
            false, false, 12, "No problems found", 0, verified, false, 1L, true, check, null, alignment,
            "FILE", null, null, null, false);
    }
}
