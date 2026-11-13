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

    private static Pick pick(List<Long> partIds, List<SubtitleSidecar> sidecars) {
        return SidecarPrecedence.pick(partIds, sidecars, SubtitleKind.FORCED);
    }

    @Test
    void aSingleFileForASingleFileEditionPlaysAtItsStart() {
        SubtitleSidecar only = sidecar(1, 10L, false);

        Pick pick = pick(List.of(10L), List.of(only));

        assertThat(pick.slots()).containsExactly(new Slot(only, 0));
        assertThat(pick.conflicting()).isEmpty();
    }

    @Test
    void nothingAttachedMeansNothingPlays() {
        assertThat(pick(TWO_PARTS, List.of()).slots()).isEmpty();
        assertThat(pick(TWO_PARTS, List.of(sidecar(1, 99L, false))).slots()).isEmpty();
        assertThat(pick(TWO_PARTS, List.of(sidecar(2, null, false))).slots()).isEmpty();
    }

    @Test
    void aWholeEditionFileBeatsPerPartFiles() {
        SubtitleSidecar whole = sidecar(1, 10L, true);

        Pick pick = pick(TWO_PARTS, List.of(sidecar(2, 10L, false), sidecar(3, 20L, false), whole));

        assertThat(pick.slots()).containsExactly(new Slot(whole, 0));
        assertThat(pick.conflicting()).isEmpty();
    }

    @Test
    void perPartFilesPlayInPartOrderEachFromItsOwnPart() {
        SubtitleSidecar second = sidecar(1, 20L, false);
        SubtitleSidecar first = sidecar(2, 10L, false);

        Pick pick = pick(TWO_PARTS, List.of(second, first));

        assertThat(pick.slots()).containsExactly(new Slot(first, 0), new Slot(second, 1));
    }

    @Test
    void aPartWithNoFileContributesNothingAndTheOthersStillPlay() {
        SubtitleSidecar second = sidecar(1, 20L, false);

        assertThat(pick(TWO_PARTS, List.of(second)).slots()).containsExactly(new Slot(second, 1));
    }

    @Test
    void twoWholeEditionFilesAreAConflictAndNothingPlaysEvenWithPerPartFiles() {
        Pick pick = pick(TWO_PARTS, List.of(sidecar(1, 10L, true), sidecar(2, 10L, true),
            sidecar(3, 20L, false)));

        assertThat(pick.slots()).isEmpty();
        assertThat(pick.conflicting()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void twoFilesForOnePartAreAConflictThatLeavesThatPartWithNone() {
        SubtitleSidecar second = sidecar(3, 20L, false);

        Pick pick = pick(TWO_PARTS, List.of(sidecar(1, 10L, false), sidecar(2, 10L, false), second));

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

        Pick pick = pick(List.of(10L), List.of(good, missing, empty));

        assertThat(pick.slots()).containsExactly(new Slot(good, 0));
        assertThat(pick.conflicting()).isEmpty();
    }

    @Test
    void aFileWithOneCueCompetes() {
        SubtitleSidecar one = sidecar(1, 10L, false);
        one.setCueCount(1);

        assertThat(pick(List.of(10L), List.of(one)).slots()).hasSize(1);
    }

    @Test
    void aMissingWholeEditionFileLetsThePerPartFilesPlay() {
        SubtitleSidecar whole = sidecar(1, 10L, true);
        whole.setPresence(Presence.MISSING);
        SubtitleSidecar part = sidecar(2, 10L, false);

        assertThat(pick(TWO_PARTS, List.of(whole, part)).slots()).containsExactly(new Slot(part, 0));
    }

    @Test
    void aFullFileNeverCompetesForForcedAndAForcedFileNeverCompetesForFull() {
        SubtitleSidecar forced = sidecar(1, 10L, false);
        SubtitleSidecar full = sidecar(2, 10L, false);
        full.setKind(SubtitleKind.FULL);
        List<SubtitleSidecar> both = List.of(forced, full);

        Pick forcedPick = SidecarPrecedence.pick(List.of(10L), both, SubtitleKind.FORCED);
        Pick fullPick = SidecarPrecedence.pick(List.of(10L), both, SubtitleKind.FULL);

        assertThat(forcedPick.slots()).containsExactly(new Slot(forced, 0));
        assertThat(forcedPick.conflicting()).isEmpty();
        assertThat(fullPick.slots()).containsExactly(new Slot(full, 0));
        assertThat(fullPick.conflicting()).isEmpty();
    }

    @Test
    void twoFullFilesForOnePartAreAConflictAndAWholeEditionFullFileBeatsPerPartFiles() {
        SubtitleSidecar en = sidecar(1, 10L, false);
        SubtitleSidecar eng = sidecar(2, 10L, false);
        en.setKind(SubtitleKind.FULL);
        eng.setKind(SubtitleKind.FULL);
        SubtitleSidecar whole = sidecar(3, 10L, true);
        whole.setKind(SubtitleKind.FULL);

        Pick clash = SidecarPrecedence.pick(List.of(10L), List.of(en, eng), SubtitleKind.FULL);
        Pick beaten = SidecarPrecedence.pick(TWO_PARTS, List.of(en, whole), SubtitleKind.FULL);

        assertThat(clash.slots()).isEmpty();
        assertThat(clash.conflicting()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(beaten.slots()).containsExactly(new Slot(whole, 0));
    }
}
