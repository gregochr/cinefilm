package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubtitleSidecarTest {

    @Test
    void thePartsKeyJoinsTheIdsInPlayOrder() {
        assertThat(SubtitleSidecar.partsKey(List.of(5L, 9L))).isEqualTo("5,9");
        assertThat(SubtitleSidecar.partsKey(List.of(5L))).isEqualTo("5");
        assertThat(SubtitleSidecar.partsKey(List.of())).isEmpty();
    }

    @Test
    void theCheckCountsOnlyWhileTheEditionHasTheSamePartsInTheSameOrder() {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setOwnerVerifiedAt(NOW);
        sidecar.setVerifiedParts("5,9");

        assertThat(sidecar.isVerifiedFor(List.of(5L, 9L))).isTrue();
        assertThat(sidecar.isVerifiedFor(List.of(5L))).isFalse();
        assertThat(sidecar.isVerifiedFor(List.of(9L, 5L))).isFalse();
        assertThat(sidecar.isVerifiedFor(List.of(5L, 9L, 12L))).isFalse();
    }

    @Test
    void aFileTheOwnerHasNotCheckedIsNeverVerifiedEvenWithMatchingParts() {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setVerifiedParts("5");

        assertThat(sidecar.isVerifiedFor(List.of(5L))).isFalse();
    }

    @Test
    void clearingTheVerificationRemovesTheTimeAndTheParts() {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setOwnerVerifiedAt(NOW);
        sidecar.setVerifiedParts("5");

        sidecar.clearVerification();

        assertThat(sidecar.getOwnerVerifiedAt()).isNull();
        assertThat(sidecar.getVerifiedParts()).isNull();
        assertThat(sidecar.isVerifiedFor(List.of(5L))).isFalse();
    }
}
