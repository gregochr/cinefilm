package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedChoice.DownloadSide;
import gregochr.cinefilm.subtitle.ForcedChoice.Input;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.PendingDownloads.Pending;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Which films still have an automatic download to come, with the repositories and the resolver faked. */
@ExtendWith(MockitoExtension.class)
class PendingDownloadsTest {

    private static final String IMDB = "tt0936501";
    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";

    @Mock
    private FilmRepository films;

    @Mock
    private EditionRepository editions;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private ForcedResolver resolver;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private ForcedDownloadBlockRepository blocks;

    private PendingDownloads pending;
    private Film film;
    private Edition edition;
    private MediaFile part;

    @BeforeEach
    void setUp() {
        pending = new PendingDownloads(films, editions, sidecars, resolver, references, blocks);
        film = film(1L, "Amelie", 2001, NOW);
        film.setImdbId(IMDB);
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        part = file(edition, "Amelie.m4v");
        part.setId(10L);
        part.setPartNumber(1);
        part.setPresence(Presence.PRESENT);
    }

    private static OpenSubtitlesResult candidate(long fileId, int fileCount) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null, fileCount);
    }

    private static String candidates(OpenSubtitlesResult... results) {
        return OpenSubtitlesResult.listToJson(List.of(results));
    }

    private static OpenSubtitlesReference stored(long fileId, int cueCount, int failedDownloads) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setFilmId(1L);
        reference.setImdbId(IMDB);
        reference.setOsFileId(fileId);
        reference.setCuesText(CUES);
        reference.setCueCount(cueCount);
        reference.setFailedDownloads(failedDownloads);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private static SubtitleSidecar sidecar(SubtitleKind kind, SubtitleSidecar.Origin origin, long fileId, int cues) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(fileId);
        row.setKind(kind);
        row.setOrigin(origin);
        row.setMediaFileId(10L);
        row.setOpenSubtitlesFileId(fileId);
        row.setPath(origin == SubtitleSidecar.Origin.OPENSUBTITLES ? "opensubtitles:40:" + fileId : "Amelie.fr.srt");
        row.setImdbId(IMDB);
        row.setCuesText(CUES);
        row.setCueCount(cues);
        row.setUpdatedAt(NOW);
        return row;
    }

    private static ForcedDownloadBlock block(SubtitleKind kind, long fileId) {
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setKind(kind);
        block.setEditionId(40L);
        block.setImdbId(IMDB);
        block.setOsFileId(fileId);
        block.setRemovedAt(NOW);
        return block;
    }

    private void fullCandidates(OpenSubtitlesResult... results) {
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(new OsFullRef(1L, IMDB, candidates(results))));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
    }

    // ---- full ----

    @Test
    void noFilmsToAskAboutIsNothingPendingAndReadsNothing() {
        assertThat(pending.full(Map.of())).isSameAs(Pending.NONE);
        assertThat(pending.forced(Map.of())).isSameAs(Pending.NONE);

        verifyNoInteractions(films, editions, sidecars, resolver, references, blocks);
    }

    @Test
    void aFilmWithFullCandidatesAndAnEditionWithNothingYetIsPendingOnBothCounts() {
        fullCandidates(candidate(5011, 1), candidate(5012, 1));

        Pending result = pending.full(Map.of(1L, IMDB));

        assertThat(result.withCandidate()).containsExactly(1L);
        assertThat(result.withDueEdition()).containsExactly(1L);
    }

    @Test
    void aFilmWhoseCandidatesWereNotAskedAboutIsLeftOut() {
        when(films.findOpenSubtitlesFull())
            .thenReturn(List.of(new OsFullRef(2L, "tt2", candidates(candidate(5011, 1)))));

        assertThat(pending.full(Map.of(1L, IMDB))).isSameAs(Pending.NONE);
        verifyNoInteractions(editions);
    }

    @Test
    void candidatesSplitOverSeveralFilesCannotBeDownloadedAndDoNotCount() {
        fullCandidates(candidate(5011, 2));

        Pending result = pending.full(Map.of(1L, IMDB));

        assertThat(result.withCandidate()).isEmpty();
        assertThat(result.withDueEdition()).containsExactly(1L);
    }

    @Test
    void aCandidateWhoseStoredReferenceIsSpentDoesNotCount() {
        fullCandidates(candidate(5011, 1));
        // Usable but short of the 300 lines of a full subtitle: final, and not promotable as full.
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 5, 0)));

        assertThat(pending.full(Map.of(1L, IMDB)).withCandidate()).isEmpty();
    }

    @Test
    void aCandidateWhoseStoredReferenceFailedOnlyOnceOrIsFullStillCounts() {
        fullCandidates(candidate(5011, 1), candidate(5012, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 0, 2),
            stored(5012, SubtitleComparison.FULL_LINES, 0)));

        assertThat(pending.full(Map.of(1L, IMDB)).withCandidate()).containsExactly(1L);
    }

    @Test
    void aCandidateWhoseReferenceFailedThreeTimesDoesNotCount() {
        fullCandidates(candidate(5011, 1));
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 0, 3)));

        assertThat(pending.full(Map.of(1L, IMDB)).withCandidate()).isEmpty();
    }

    @Test
    void anEditionAtTheCapIsNotDueButItsFilmStillHasACandidate() {
        fullCandidates(candidate(5011, 1));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL)).thenReturn(List.of(
            block(SubtitleKind.FULL, 1), block(SubtitleKind.FULL, 2), block(SubtitleKind.FULL, 3)));

        Pending result = pending.full(Map.of(1L, IMDB));

        assertThat(result.withCandidate()).containsExactly(1L);
        assertThat(result.withDueEdition()).isEmpty();
    }

    @Test
    void anEditionOneShortOfTheCapIsStillDue() {
        fullCandidates(candidate(5011, 1));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL)).thenReturn(List.of(
            block(SubtitleKind.FULL, 1), block(SubtitleKind.FULL, 2)));

        assertThat(pending.full(Map.of(1L, IMDB)).withDueEdition()).containsExactly(1L);
    }

    @Test
    void aForcedBlockNeverUsesUpTheFullCap() {
        fullCandidates(candidate(5011, 1));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL)).thenReturn(List.of(
            block(SubtitleKind.FORCED, 1), block(SubtitleKind.FORCED, 2), block(SubtitleKind.FORCED, 3)));

        assertThat(pending.full(Map.of(1L, IMDB)).withDueEdition()).containsExactly(1L);
    }

    @Test
    void anEditionWithAnOwnersFullFileOrALiveDownloadIsNotDue() {
        fullCandidates(candidate(5011, 1));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL))
            .thenReturn(List.of(sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.FILE, 1, 400)));

        assertThat(pending.full(Map.of(1L, IMDB)).withDueEdition()).isEmpty();

        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL))
            .thenReturn(List.of(sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.OPENSUBTITLES, 5011, 400)));

        assertThat(pending.full(Map.of(1L, IMDB)).withDueEdition()).isEmpty();
    }

    @Test
    void anOwnersFileWithNoLinesDoesNotMakeAnEditionNotDue() {
        fullCandidates(candidate(5011, 1));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL)).thenReturn(List.of(
            sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.FILE, 1, 0)));

        assertThat(pending.full(Map.of(1L, IMDB)).withDueEdition()).containsExactly(1L);
    }

    @Test
    void anEditionWhosePartIsMissingFromDiskIsNotDue() {
        fullCandidates(candidate(5011, 1));
        part.setPresence(Presence.MISSING);

        Pending result = pending.full(Map.of(1L, IMDB));

        assertThat(result.withCandidate()).containsExactly(1L);
        assertThat(result.withDueEdition()).isEmpty();
    }

    @Test
    void oneDueEditionAmongSeveralMakesTheFilmDue() {
        fullCandidates(candidate(5011, 1));
        Edition cut = edition(film, 41L, "Cut", 95 * MIN, null);
        MediaFile cutPart = file(cut, "Amelie.Cut.m4v");
        cutPart.setId(11L);
        cutPart.setPartNumber(1);
        cutPart.setPresence(Presence.PRESENT);
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition, cut));
        // The first edition is at the cap; the second has no blocks.
        ForcedDownloadBlock one = block(SubtitleKind.FULL, 1);
        ForcedDownloadBlock two = block(SubtitleKind.FULL, 2);
        ForcedDownloadBlock three = block(SubtitleKind.FULL, 3);
        when(blocks.findByEditionIdInAndKind(List.of(40L, 41L), SubtitleKind.FULL))
            .thenReturn(List.of(one, two, three));

        assertThat(pending.full(Map.of(1L, IMDB)).withDueEdition()).containsExactly(1L);
    }

    // ---- forced ----

    private Resolution resolution(boolean conflict, boolean embedded) {
        Pick pick = new Pick(List.of(), conflict ? Set.of(5L, 6L) : Set.of());
        return new Resolution(edition, List.of(10L), pick, null, null, null, null, Map.of(), List.of(), embedded,
            ForcedChoice.decide(new Input(null, List.<DownloadSide>of(), embedded)), Map.of());
    }

    @Test
    void anEditionWithNoSubtitleAtAllIsDueForAForcedDownload() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(false, false)));

        Pending result = pending.forced(Map.of(1L, IMDB));

        assertThat(result.withDueEdition()).containsExactly(1L);
        assertThat(result.withCandidate()).isEmpty();
    }

    @Test
    void aConflictOrAnEmbeddedTrackMeansNothingIsDue() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(true, false)));

        assertThat(pending.forced(Map.of(1L, IMDB)).withDueEdition()).isEmpty();

        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(false, true)));

        assertThat(pending.forced(Map.of(1L, IMDB)).withDueEdition()).isEmpty();
    }

    @Test
    void aForcedEditionAtTheCapIsNotDueAndOneBelowItIs() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(false, false)));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            block(SubtitleKind.FORCED, 1), block(SubtitleKind.FORCED, 2)));

        assertThat(pending.forced(Map.of(1L, IMDB)).withDueEdition()).containsExactly(1L);

        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            block(SubtitleKind.FORCED, 1), block(SubtitleKind.FORCED, 2), block(SubtitleKind.FORCED, 3)));

        assertThat(pending.forced(Map.of(1L, IMDB)).withDueEdition()).isEmpty();
    }

    @Test
    void aFullBlockNeverUsesUpTheForcedCap() {
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(false, false)));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            block(SubtitleKind.FULL, 1), block(SubtitleKind.FULL, 2), block(SubtitleKind.FULL, 3)));

        assertThat(pending.forced(Map.of(1L, IMDB)).withDueEdition()).containsExactly(1L);
    }

    @Test
    void aForcedDownloadWhosePartIsMissingIsNotDue() {
        part.setPresence(Presence.MISSING);
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        when(resolver.resolve(List.of())).thenReturn(Map.of());

        assertThat(pending.forced(Map.of(1L, IMDB)).withDueEdition()).isEmpty();
    }
}
