package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** When an edition is due a download, and which OpenSubtitles file it gets: promote a stored one, else fetch. */
class DownloadPlanTest {

    private static final FileSide FAILED = new FileSide(new Assessment(Trust.FAILED, Proof.NONE), 12);
    private static final FileSide UNCHECKED = new FileSide(new Assessment(Trust.UNCHECKED, Proof.NONE), 12);
    private static final FileSide PROVEN = new FileSide(new Assessment(Trust.PROVEN, Proof.AGREES), 12);

    /** Ten cues a hundred seconds apart: what the owner's file says. */
    private static final List<SubtitleCue> SERVED = spaced(10, 100_000);

    private static List<SubtitleCue> spaced(int count, long gapMs) {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            cues.add(new SubtitleCue(i * gapMs, i * gapMs + 2_000, "line " + i));
        }
        return cues;
    }

    /** Ten cues at irregular times that match almost none of {@link #SERVED}: another cut. */
    private static List<SubtitleCue> otherCut() {
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            long start = 13_000L + (long) i * i * 9_700 + i * 1_300L;
            cues.add(new SubtitleCue(start, start + 2_000, "other " + i));
        }
        return cues;
    }

    private static OpenSubtitlesReference reference(long fileId, List<SubtitleCue> cues) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(fileId);
        reference.setFilmId(1L);
        reference.setImdbId("tt1");
        reference.setOsFileId(fileId);
        reference.setCuesText(SrtParser.format(cues));
        reference.setCueCount(cues.size());
        reference.setFetchedAt(Instant.EPOCH);
        return reference;
    }

    private static OpenSubtitlesResult result(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Rel" + fileId, 23.976, 5, true, false, null, null, 1);
    }

    private static Map<Long, OpenSubtitlesReference> stored(OpenSubtitlesReference... references) {
        Map<Long, OpenSubtitlesReference> map = new HashMap<>();
        for (OpenSubtitlesReference reference : references) {
            map.put(reference.getOsFileId(), reference);
        }
        return map;
    }

    @Test
    void anEditionWithNothingThatPlaysIsMissingAndOneWithAFailedFileNeedsAReplacement() {
        assertThat(DownloadPlan.kind(true, false, null, false, false)).contains(Kind.MISSING);
        assertThat(DownloadPlan.kind(true, false, FAILED, false, false)).contains(Kind.REPLACEMENT);
    }

    @Test
    void nothingIsDueWhenTheNeedIsNotShown() {
        assertThat(DownloadPlan.kind(false, false, null, false, false)).isEmpty();
        assertThat(DownloadPlan.kind(false, false, FAILED, false, false)).isEmpty();
    }

    @Test
    void nothingIsDueForAFileNothingHasFailed() {
        assertThat(DownloadPlan.kind(true, false, UNCHECKED, false, false)).isEmpty();
        assertThat(DownloadPlan.kind(true, false, PROVEN, false, false)).isEmpty();
    }

    @Test
    void nothingIsDueForAConflictOfTheOwnersFilesOrWhileADownloadIsWaitingToBeProven() {
        assertThat(DownloadPlan.kind(true, true, null, false, false)).isEmpty();
        assertThat(DownloadPlan.kind(true, false, null, true, false)).isEmpty();
        assertThat(DownloadPlan.kind(true, false, FAILED, true, false)).isEmpty();
    }

    @Test
    void anEmbeddedForcedTrackMeansNothingIsMissingAndNothingNeedsReplacing() {
        assertThat(DownloadPlan.kind(true, false, null, false, true)).isEmpty();
        assertThat(DownloadPlan.kind(true, false, FAILED, false, true)).isEmpty();
    }

    @Test
    void aReferenceIsPromotableWhenUsableAndUnderThreeHundredLines() {
        assertThat(DownloadPlan.promotable(reference(5001, spaced(299, 1_000)))).isTrue();
        assertThat(DownloadPlan.promotable(reference(5001, spaced(300, 1_000)))).isFalse();
        assertThat(DownloadPlan.promotable(reference(5001, List.of()))).isFalse();
    }

    @Test
    void theReferenceTheWalkKeptIsPromotedWhenItsCutFits() {
        OpenSubtitlesReference kept = reference(5002, SERVED);
        OpenSubtitlesReference first = reference(5001, SERVED);

        Optional<OpenSubtitlesReference> promoted = DownloadPlan.promotion(List.of(result(5001), result(5002)), 5002L,
            stored(first, kept), Set.of(5001L, 5002L), Set.of(), SERVED);

        assertThat(promoted).containsSame(kept);
    }

    @Test
    void withNoKeptFileTheFirstCandidateInTheEditionsOrderThatFitsIsPromoted() {
        OpenSubtitlesReference first = reference(5001, SERVED);
        OpenSubtitlesReference second = reference(5002, SERVED);

        Optional<OpenSubtitlesReference> promoted = DownloadPlan.promotion(List.of(result(5001), result(5002)), null,
            stored(first, second), Set.of(5001L, 5002L), Set.of(), SERVED);

        assertThat(promoted).containsSame(first);
    }

    @Test
    void aReferenceThatLooksLikeAnotherCutIsNotPromotedAndTheNextOneIs() {
        OpenSubtitlesReference other = reference(5001, otherCut());
        OpenSubtitlesReference fits = reference(5002, SERVED);
        assertThat(ReferenceWalk.looksLikeOtherCut(SubtitleComparison.compare(SERVED, otherCut()))).isTrue();

        Optional<OpenSubtitlesReference> promoted = DownloadPlan.promotion(List.of(result(5001), result(5002)), 5001L,
            stored(other, fits), Set.of(5001L, 5002L), Set.of(), SERVED);

        assertThat(promoted).containsSame(fits);
    }

    @Test
    void aFullReferenceIsNeverPromoted() {
        List<SubtitleCue> full = spaced(300, 10_000);
        OpenSubtitlesReference reference = reference(5001, full);

        assertThat(DownloadPlan.promotion(List.of(result(5001)), 5001L, stored(reference), Set.of(5001L), Set.of(),
            spaced(10, 100_000))).isEmpty();
    }

    @Test
    void aReferenceTheEditionWasNeverComparedWithOrThatIsBlockedOrAlreadyADownloadIsNotPromoted() {
        OpenSubtitlesReference reference = reference(5001, SERVED);
        Map<Long, OpenSubtitlesReference> stored = stored(reference);

        assertThat(DownloadPlan.promotion(List.of(result(5001)), null, stored, Set.of(), Set.of(), SERVED)).isEmpty();
        assertThat(DownloadPlan.promotion(List.of(result(5001)), 5001L, stored, Set.of(5001L), Set.of(5001L),
            SERVED)).isEmpty();
    }

    @Test
    void aReferenceThatIsNotOneOfTheFilmsSingleFileCandidatesOrIsUnusableIsNotPromoted() {
        OpenSubtitlesReference unusable = reference(5002, List.of());

        assertThat(DownloadPlan.promotion(List.of(result(5002)), null, stored(unusable), Set.of(5002L), Set.of(),
            SERVED)).isEmpty();
        assertThat(DownloadPlan.promotion(List.of(), 5001L, stored(reference(5001, SERVED)), Set.of(5001L), Set.of(),
            SERVED)).isEmpty();
    }

    @Test
    void aKeptFileThatIsNotStoredIsSkipped() {
        assertThat(DownloadPlan.promotion(List.of(result(5001)), 9999L, stored(), Set.of(9999L), Set.of(), SERVED))
            .isEmpty();
    }

    @Test
    void theFilesToFetchLeaveOutBlockedDownloadedComparedAndSpentOnes() {
        List<OpenSubtitlesResult> ranked = List.of(result(1), result(2), result(3), result(4), result(5), result(6));
        OpenSubtitlesReference unusable = reference(5, List.of());
        unusable.setFailedDownloads(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS);
        OpenSubtitlesReference full = reference(6, spaced(300, 1_000));
        OpenSubtitlesReference good = reference(4, SERVED);

        List<OpenSubtitlesResult> toFetch = DownloadPlan.toFetch(ranked, Set.of(1L), Set.of(2L),
            stored(unusable, full, good));

        // 1 is blocked or a download, 2 was compared, 5 could not be read, 6 is full subtitles; 4 is stored and fine.
        assertThat(toFetch).extracting(OpenSubtitlesResult::fileId).containsExactly(3L, 4L);
    }

    @Test
    void aReferenceThatFailedOnlyOnceIsStillWorthFetching() {
        OpenSubtitlesReference once = reference(3, List.of());
        once.setFailedDownloads(1);

        assertThat(DownloadPlan.toFetch(List.of(result(3)), Set.of(), Set.of(), stored(once)))
            .extracting(OpenSubtitlesResult::fileId).containsExactly(3L);
    }

    @Test
    void anEditionMayEverBeGivenThreeFilesCountingTheRemovedOnes() {
        assertThat(DownloadPlan.MAX_DOWNLOADS).isEqualTo(3);
        assertThat(DownloadPlan.remaining(Set.of())).isEqualTo(3);
        assertThat(DownloadPlan.remaining(Set.of(1L, 2L))).isEqualTo(1);
        assertThat(DownloadPlan.remaining(Set.of(1L, 2L, 3L))).isZero();
        assertThat(DownloadPlan.remaining(Set.of(1L, 2L, 3L, 4L))).isZero();
    }
}
