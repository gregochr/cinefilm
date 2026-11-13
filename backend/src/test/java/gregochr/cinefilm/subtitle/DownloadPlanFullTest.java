package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The per-kind rules of the download plan: lines that make a file usable, files left to fetch, when full is due. */
class DownloadPlanFullTest {

    private static OpenSubtitlesReference reference(long fileId, int cueCount) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(fileId);
        reference.setFilmId(1L);
        reference.setImdbId("tt1");
        reference.setOsFileId(fileId);
        reference.setCuesText(cueCount == 0 ? "" : "1\n00:00:01,000 --> 00:00:02,000\nx\n\n");
        reference.setCueCount(cueCount);
        reference.setFetchedAt(Instant.EPOCH);
        return reference;
    }

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null);
    }

    @Test
    void aFullFileNeedsAtLeastThreeHundredLinesAndAForcedOneFewer() {
        assertThat(DownloadPlan.promotable(reference(1, 299), SubtitleKind.FULL)).isFalse();
        assertThat(DownloadPlan.promotable(reference(1, 300), SubtitleKind.FULL)).isTrue();
        assertThat(DownloadPlan.promotable(reference(1, 301), SubtitleKind.FULL)).isTrue();
        assertThat(DownloadPlan.promotable(reference(1, 299), SubtitleKind.FORCED)).isTrue();
        assertThat(DownloadPlan.promotable(reference(1, 300), SubtitleKind.FORCED)).isFalse();
        assertThat(DownloadPlan.promotable(reference(1, 301), SubtitleKind.FORCED)).isFalse();
    }

    @Test
    void anUnusableReferenceIsNeverPromotableForEitherKind() {
        assertThat(DownloadPlan.promotable(reference(1, 0), SubtitleKind.FULL)).isFalse();
        assertThat(DownloadPlan.promotable(reference(1, 0), SubtitleKind.FORCED)).isFalse();
        assertThat(DownloadPlan.promotable(reference(1, 0))).isFalse();
    }

    @Test
    void theKindlessPromotableIsTheForcedRule() {
        assertThat(DownloadPlan.promotable(reference(1, 299))).isTrue();
        assertThat(DownloadPlan.promotable(reference(1, 300))).isFalse();
    }

    @Test
    void aStoredFileIsSpentForAKindOnlyWhenItIsFinalAndNotUsableAsThatKind() {
        List<OpenSubtitlesResult> ranked = List.of(candidate(5011), candidate(5012), candidate(5013));
        Map<Long, OpenSubtitlesReference> stored = Map.of(5011L, reference(5011, 299), 5012L, reference(5012, 300));

        assertThat(DownloadPlan.toFetch(ranked, Set.of(), Set.of(), stored, SubtitleKind.FULL))
            .extracting(OpenSubtitlesResult::fileId).containsExactly(5012L, 5013L);
        assertThat(DownloadPlan.toFetch(ranked, Set.of(), Set.of(), stored, SubtitleKind.FORCED))
            .extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5013L);
        assertThat(DownloadPlan.toFetch(ranked, Set.of(), Set.of(), stored))
            .extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5013L);
    }

    @Test
    void blockedAndComparedFilesAreLeftOutForEveryKind() {
        List<OpenSubtitlesResult> ranked = List.of(candidate(5011), candidate(5012), candidate(5013));

        assertThat(DownloadPlan.toFetch(ranked, Set.of(5011L), Set.of(5012L), Map.of(), SubtitleKind.FULL))
            .extracting(OpenSubtitlesResult::fileId).containsExactly(5013L);
    }

    @Test
    void aFullDownloadIsDueOnlyWithoutAnOwnersFileAndWithoutADownload() {
        assertThat(DownloadPlan.fullDue(false, false)).isTrue();
        assertThat(DownloadPlan.fullDue(true, false)).isFalse();
        assertThat(DownloadPlan.fullDue(false, true)).isFalse();
        assertThat(DownloadPlan.fullDue(true, true)).isFalse();
    }
}
