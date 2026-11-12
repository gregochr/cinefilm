package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SidecarPrecedenceTest {

    private static final List<Long> TWO_PARTS = List.of(10L, 20L);

    private static SubtitleSidecar sidecar(long id, Long fileId, boolean whole) {
        SubtitleSidecar sidecar = new SubtitleSidecar();
        sidecar.setId(id);
        sidecar.setPath("s" + id + ".en.forced.srt");
        sidecar.setMediaFileId(fileId);
        sidecar.setWholeEdition(whole);
        sidecar.setCueCount(3);
        sidecar.setUpdatedAt(Instant.EPOCH);
        return sidecar;
    }

    @Test
    void aSingleFileForASingleFileEditionPlaysAtItsStart() {
        SubtitleSidecar only = sidecar(1, 10L, false);

        Pick pick = SidecarPrecedence.pick(List.of(10L), List.of(only));

        assertThat(pick.slots()).containsExactly(new Slot(only, 0));
        assertThat(pick.conflicting()).isEmpty();
    }

    @Test
    void nothingAttachedMeansNothingPlays() {
        assertThat(SidecarPrecedence.pick(TWO_PARTS, List.of()).slots()).isEmpty();
        assertThat(SidecarPrecedence.pick(TWO_PARTS, List.of(sidecar(1, 99L, false))).slots()).isEmpty();
        assertThat(SidecarPrecedence.pick(TWO_PARTS, List.of(sidecar(2, null, false))).slots()).isEmpty();
    }

    @Test
    void aWholeEditionFileBeatsPerPartFiles() {
        SubtitleSidecar whole = sidecar(1, 10L, true);

        Pick pick = SidecarPrecedence.pick(TWO_PARTS, List.of(sidecar(2, 10L, false), sidecar(3, 20L, false), whole));

        assertThat(pick.slots()).containsExactly(new Slot(whole, 0));
        assertThat(pick.conflicting()).isEmpty();
    }

    @Test
    void perPartFilesPlayInPartOrderEachFromItsOwnPart() {
        SubtitleSidecar second = sidecar(1, 20L, false);
        SubtitleSidecar first = sidecar(2, 10L, false);

        Pick pick = SidecarPrecedence.pick(TWO_PARTS, List.of(second, first));

        assertThat(pick.slots()).containsExactly(new Slot(first, 0), new Slot(second, 1));
    }

    @Test
    void aPartWithNoFileContributesNothingAndTheOthersStillPlay() {
        SubtitleSidecar second = sidecar(1, 20L, false);

        assertThat(SidecarPrecedence.pick(TWO_PARTS, List.of(second)).slots()).containsExactly(new Slot(second, 1));
    }

    @Test
    void twoWholeEditionFilesAreAConflictAndNothingPlaysEvenWithPerPartFiles() {
        Pick pick = SidecarPrecedence.pick(TWO_PARTS, List.of(sidecar(1, 10L, true), sidecar(2, 10L, true),
            sidecar(3, 20L, false)));

        assertThat(pick.slots()).isEmpty();
        assertThat(pick.conflicting()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void twoFilesForOnePartAreAConflictThatLeavesThatPartWithNone() {
        SubtitleSidecar second = sidecar(3, 20L, false);

        Pick pick = SidecarPrecedence.pick(TWO_PARTS, List.of(sidecar(1, 10L, false), sidecar(2, 10L, false), second));

        assertThat(pick.slots()).containsExactly(new Slot(second, 1));
        assertThat(pick.conflicting()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void aMissingFileAndAFileWithNoCuesDoNotCompete() {
        SubtitleSidecar good = sidecar(1, 10L, false);
        SubtitleSidecar missing = sidecar(2, 10L, false);
        missing.setPresence(Presence.MISSING);
        SubtitleSidecar empty = sidecar(3, 10L, false);
        empty.setCueCount(0);

        Pick pick = SidecarPrecedence.pick(List.of(10L), List.of(good, missing, empty));

        assertThat(pick.slots()).containsExactly(new Slot(good, 0));
        assertThat(pick.conflicting()).isEmpty();
    }

    @Test
    void aFileWithOneCueCompetes() {
        SubtitleSidecar one = sidecar(1, 10L, false);
        one.setCueCount(1);

        assertThat(SidecarPrecedence.pick(List.of(10L), List.of(one)).slots()).hasSize(1);
    }

    @Test
    void aMissingWholeEditionFileLetsThePerPartFilesPlay() {
        SubtitleSidecar whole = sidecar(1, 10L, true);
        whole.setPresence(Presence.MISSING);
        SubtitleSidecar part = sidecar(2, 10L, false);

        assertThat(SidecarPrecedence.pick(TWO_PARTS, List.of(whole, part)).slots()).containsExactly(new Slot(part, 0));
    }
}
