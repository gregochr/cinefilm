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

    @Test
    void anAutomaticOffsetIsDroppedBackToNeverSet() {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setOffsetMs(1_200);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);

        sidecar.dropAutoOffset();

        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.getOffsetSource()).isNull();
    }

    @Test
    void anOffsetTheOwnerSetIsNeverDropped() {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setOffsetMs(-250);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);

        sidecar.dropAutoOffset();

        assertThat(sidecar.getOffsetMs()).isEqualTo(-250);
        assertThat(sidecar.getOffsetSource()).isEqualTo(SubtitleSidecar.OffsetSource.OWNER);
    }

    @Test
    void aFileWhoseOffsetWasNeverSetIsLeftAlone() {
        SubtitleSidecar sidecar = new SubtitleSidecar();

        sidecar.dropAutoOffset();

        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.getOffsetSource()).isNull();
    }

    @Test
    void retargetingDropsAnAutomaticOffsetOnlyWhenTheFileOrTheWholeFlagChanges() {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setMediaFileId(10L);
        sidecar.setOffsetMs(900);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);

        sidecar.retarget(10L, false);
        assertThat(sidecar.getOffsetMs()).isEqualTo(900);

        sidecar.retarget(10L, true);
        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.isWholeEdition()).isTrue();

        sidecar.setOffsetMs(900);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
        sidecar.retarget(11L, true);
        assertThat(sidecar.getOffsetMs()).isZero();
        assertThat(sidecar.getMediaFileId()).isEqualTo(11L);

        sidecar.setOffsetMs(-250);
        sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.OWNER);
        sidecar.retarget(null, false);
        assertThat(sidecar.getOffsetMs()).isEqualTo(-250);
        assertThat(sidecar.getMediaFileId()).isNull();
    }
}
