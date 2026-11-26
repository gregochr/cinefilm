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
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.ForcedChoice.Input;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.PendingDownloads.Pending;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Which films the scheduler would download for, with the repositories and the resolver faked. The decision itself is
 * {@link ForcedDownloadService#fullDueFor} and {@link ForcedDownloadService#dueFor}, which this runs over the same
 * facts as the scheduler.
 */
@ExtendWith(MockitoExtension.class)
class PendingDownloadsTest {

    private static final String IMDB = "tt0936501";
    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";
    private static final Assessment FAILED = new Assessment(Trust.FAILED, Proof.NONE);

    @Mock
    private FilmRates filmRates;

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

    @Mock
    private ForcedCheckCandidateRepository ledger;

    private PendingDownloads pending;
    private PendingDownloads pendingWithDownloadsOff;
    private Film film;
    private Edition edition;
    private MediaFile part;

    @BeforeEach
    void setUp() {
        pending = new PendingDownloads(films, editions, sidecars, resolver, references, blocks, ledger,
            OpenSubtitlesSwitches.on(), filmRates);
        pendingWithDownloadsOff = new PendingDownloads(films, editions, sidecars, resolver, references, blocks,
            ledger, new OpenSubtitlesSwitches(true, false), filmRates);
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

    private static ForcedDownloadBlock block(SubtitleKind kind, long editionId, long fileId) {
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setKind(kind);
        block.setEditionId(editionId);
        block.setImdbId(IMDB);
        block.setOsFileId(fileId);
        block.setRemovedAt(NOW);
        return block;
    }

    private static ForcedCheckCandidate compared(SubtitleKind kind, long editionId, long fileId) {
        ForcedCheckCandidate row = new ForcedCheckCandidate();
        row.setEditionId(editionId);
        row.setImdbId(IMDB);
        row.setKind(kind);
        row.setOsFileId(fileId);
        row.setTriedAt(NOW);
        return row;
    }

    private Edition secondEdition() {
        Edition cut = edition(film, 41L, "Cut", 95 * MIN, null);
        MediaFile cutPart = file(cut, "Amelie.Cut.m4v");
        cutPart.setId(11L);
        cutPart.setPartNumber(1);
        cutPart.setPresence(Presence.PRESENT);
        return cut;
    }

    // ---- full ----

    private void fullCandidates(OpenSubtitlesResult... results) {
        when(films.findOpenSubtitlesFull()).thenReturn(List.of(new OsFullRef(1L, IMDB, candidates(results))));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
    }

    private boolean fullDue() {
        return pending.full(Map.of(1L, IMDB)).due().contains(1L);
    }

    @Test
    void noFilmsToAskAboutIsNothingPendingAndReadsNothing() {
        assertThat(pending.full(Map.of())).isSameAs(Pending.NONE);
        assertThat(pending.forced(Map.of())).isSameAs(Pending.NONE);

        verifyNoInteractions(films, editions, sidecars, resolver, references, blocks, ledger);
    }

    @Test
    void aFilmWithAFullCandidateAndAnEditionWithNothingYetIsDue() {
        fullCandidates(candidate(5011, 1), candidate(5012, 1));

        assertThat(fullDue()).isTrue();
    }

    @Test
    void aFilmWhoseCandidatesWereNotAskedAboutIsLeftOut() {
        when(films.findOpenSubtitlesFull())
            .thenReturn(List.of(new OsFullRef(2L, "tt2", candidates(candidate(5011, 1)))));

        assertThat(pending.full(Map.of(1L, IMDB))).isSameAs(Pending.NONE);
        verifyNoInteractions(editions);
    }

    @Test
    void candidatesSplitOverSeveralFilesCannotBeDownloadedSoNothingIsDue() {
        fullCandidates(candidate(5011, 2));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void aBlockedOnlyCandidateIsNotDue() {
        fullCandidates(candidate(5011, 1));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL))
            .thenReturn(List.of(block(SubtitleKind.FULL, 40, 5011)));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void aLedgeredOnlyCandidateIsNotDue() {
        fullCandidates(candidate(5011, 1));
        when(ledger.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL))
            .thenReturn(List.of(compared(SubtitleKind.FULL, 40, 5011)));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void aLedgerRowOfAnotherEditionOrImdbIdDoesNotUseUpTheCandidate() {
        fullCandidates(candidate(5011, 1));
        ForcedCheckCandidate otherImdb = compared(SubtitleKind.FULL, 40, 5011);
        otherImdb.setImdbId("tt0000009");
        when(ledger.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL))
            .thenReturn(List.of(otherImdb, compared(SubtitleKind.FULL, 99, 5011)));

        assertThat(fullDue()).isTrue();
    }

    @Test
    void anEditionAtTheCapIsNotDueAndOneShortOfItIs() {
        fullCandidates(candidate(5011, 1));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL)).thenReturn(List.of(
            block(SubtitleKind.FULL, 40, 1), block(SubtitleKind.FULL, 40, 2)));

        assertThat(fullDue()).isTrue();

        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL)).thenReturn(List.of(
            block(SubtitleKind.FULL, 40, 1), block(SubtitleKind.FULL, 40, 2), block(SubtitleKind.FULL, 40, 3)));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void aForcedBlockNeverUsesUpTheFullCapOrACandidate() {
        fullCandidates(candidate(5011, 1));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FULL)).thenReturn(List.of(
            block(SubtitleKind.FORCED, 40, 5011), block(SubtitleKind.FORCED, 40, 2),
            block(SubtitleKind.FORCED, 40, 3)));

        assertThat(fullDue()).isTrue();
    }

    @Test
    void anEditionWithAnOwnersFullFileOrALiveFullDownloadIsNotDue() {
        fullCandidates(candidate(5011, 1));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL))
            .thenReturn(List.of(sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.FILE, 1, 400)));

        assertThat(fullDue()).isFalse();

        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL))
            .thenReturn(List.of(sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.OPENSUBTITLES, 5099, 400)));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void anOwnersFullFileWithNoLinesDoesNotMakeAnEditionNotDue() {
        fullCandidates(candidate(5011, 1));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FULL))
            .thenReturn(List.of(sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.FILE, 1, 0)));

        assertThat(fullDue()).isTrue();
    }

    @Test
    void aCandidateWhoseStoredReferenceIsSpentIsNotDueAndOneThatFailedTwiceIs() {
        fullCandidates(candidate(5011, 1));
        // Usable but short of the 300 lines of a full subtitle: final, and not promotable as full.
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 5, 0)));

        assertThat(fullDue()).isFalse();

        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 0, 2)));

        assertThat(fullDue()).isTrue();

        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 0, 3)));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void aCandidateBlockedOnOneEditionIsStillDueOnAnotherEdition() {
        fullCandidates(candidate(5011, 1));
        Edition cut = secondEdition();
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition, cut));
        when(blocks.findByEditionIdInAndKind(List.of(40L, 41L), SubtitleKind.FULL))
            .thenReturn(List.of(block(SubtitleKind.FULL, 40, 5011)));

        assertThat(fullDue()).isTrue();
    }

    @Test
    void aFilmIsNotDueWhenNoEditionCanTakeAnyCandidate() {
        fullCandidates(candidate(5011, 1));
        Edition cut = secondEdition();
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition, cut));
        // The one candidate is blocked on edition 40, and edition 41 has the owner's full file.
        when(blocks.findByEditionIdInAndKind(List.of(40L, 41L), SubtitleKind.FULL))
            .thenReturn(List.of(block(SubtitleKind.FULL, 40, 5011)));
        SubtitleSidecar owner = sidecar(SubtitleKind.FULL, SubtitleSidecar.Origin.FILE, 1, 400);
        owner.setMediaFileId(11L);
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L, 11L), SubtitleKind.FULL)).thenReturn(List.of(owner));

        assertThat(fullDue()).isFalse();
    }

    @Test
    void anEditionWhosePartIsMissingStillCountsBecauseTheSchedulerIncludesIt() {
        fullCandidates(candidate(5011, 1));
        part.setPresence(Presence.MISSING);

        assertThat(fullDue()).isTrue();
    }

    // ---- forced ----

    private void forcedCandidates(String json) {
        when(films.findOpenSubtitlesBest()).thenReturn(List.of(new OsBestRef(1L, IMDB, "{}", json)));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
    }

    private boolean forcedDue() {
        return pending.forced(Map.of(1L, IMDB)).due().contains(1L);
    }

    private Resolution resolution(Assessment fileAssessment, boolean conflict, boolean embedded) {
        SubtitleSidecar owner = sidecar(SubtitleKind.FORCED, SubtitleSidecar.Origin.FILE, 5, 1);
        List<Slot> slots = fileAssessment == null || conflict ? List.of() : List.of(new Slot(owner, 0));
        Pick pick = new Pick(slots, conflict ? Set.of(5L, 6L) : Set.of());
        FileSide side = fileAssessment == null || conflict ? null : new FileSide(fileAssessment, 1);
        return new Resolution(edition, List.of(10L), pick, slots.isEmpty() ? null : "fv",
            fileAssessment == null || conflict ? null : fileAssessment, null, null, Map.of(), List.of(), embedded,
            ForcedChoice.decide(new Input(side, List.of(), embedded)), Map.of());
    }

    private void resolves(Resolution resolution) {
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution));
    }

    @Test
    void aMissingEditionWithASingleFileCandidateIsDueForAForcedDownload() {
        forcedCandidates(candidates(candidate(5011, 1)));
        resolves(resolution(null, false, false));

        assertThat(forcedDue()).isTrue();
    }

    @Test
    void aFilmWithNoStoredBestResultOrNoCandidatesJsonIsNotAsked() {
        when(films.findOpenSubtitlesBest()).thenReturn(List.of(new OsBestRef(2L, IMDB, "{}", "[]")));

        assertThat(pending.forced(Map.of(1L, IMDB))).isSameAs(Pending.NONE);

        when(films.findOpenSubtitlesBest()).thenReturn(List.of(new OsBestRef(1L, IMDB, "{}", null)));

        assertThat(pending.forced(Map.of(1L, IMDB))).isSameAs(Pending.NONE);
        verifyNoInteractions(editions);
    }

    @Test
    void aCountAboveZeroWithOnlyMultiFileCandidatesIsNotDue() {
        forcedCandidates(candidates(candidate(5011, 2), candidate(5012, 3)));
        resolves(resolution(null, false, false));

        assertThat(forcedDue()).isFalse();
    }

    @Test
    void aConflictOrAnEmbeddedTrackMeansNothingIsDue() {
        forcedCandidates(candidates(candidate(5011, 1)));
        resolves(resolution(null, true, false));

        assertThat(forcedDue()).isFalse();

        resolves(resolution(null, false, true));

        assertThat(forcedDue()).isFalse();
    }

    @Test
    void aForcedEditionAtTheCapIsNotDueAndOneBelowItIs() {
        forcedCandidates(candidates(candidate(5011, 1)));
        resolves(resolution(null, false, false));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            block(SubtitleKind.FORCED, 40, 1), block(SubtitleKind.FORCED, 40, 2)));

        assertThat(forcedDue()).isTrue();

        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            block(SubtitleKind.FORCED, 40, 1), block(SubtitleKind.FORCED, 40, 2), block(SubtitleKind.FORCED, 40, 3)));

        assertThat(forcedDue()).isFalse();
    }

    @Test
    void aFullBlockNeverUsesUpTheForcedCap() {
        forcedCandidates(candidates(candidate(5011, 1)));
        resolves(resolution(null, false, false));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            block(SubtitleKind.FULL, 40, 1), block(SubtitleKind.FULL, 40, 2), block(SubtitleKind.FULL, 40, 3)));

        assertThat(forcedDue()).isTrue();
    }

    @Test
    void everyCandidateBlockedOrLedgeredLeavesAMissingEditionNotDue() {
        forcedCandidates(candidates(candidate(5011, 1), candidate(5012, 1)));
        resolves(resolution(null, false, false));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED))
            .thenReturn(List.of(block(SubtitleKind.FORCED, 40, 5011)));
        when(ledger.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED))
            .thenReturn(List.of(compared(SubtitleKind.FORCED, 40, 5012)));

        assertThat(forcedDue()).isFalse();
    }

    @Test
    void aReplacementEditionWhoseCandidatesAreAllLedgeredIsNotDueButOneWithACandidateLeftIs() {
        forcedCandidates(candidates(candidate(5011, 1), candidate(5012, 1)));
        resolves(resolution(FAILED, false, false));
        when(ledger.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(
            compared(SubtitleKind.FORCED, 40, 5011), compared(SubtitleKind.FORCED, 40, 5012)));

        assertThat(forcedDue()).isFalse();

        when(ledger.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED))
            .thenReturn(List.of(compared(SubtitleKind.FORCED, 40, 5011)));

        assertThat(forcedDue()).isTrue();
    }

    @Test
    void aReplacementEditionWhoseCandidatesAreAllBlockedIsNotDue() {
        forcedCandidates(candidates(candidate(5011, 1)));
        resolves(resolution(FAILED, false, false));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED))
            .thenReturn(List.of(block(SubtitleKind.FORCED, 40, 5011)));

        assertThat(forcedDue()).isFalse();
    }

    @Test
    void aSpentStoredReferenceLeavesNothingToFetch() {
        forcedCandidates(candidates(candidate(5011, 1)));
        resolves(resolution(null, false, false));
        // Final and usable, but 300 lines is full subtitles: not promotable as forced.
        when(references.findByFilmIdIn(Set.of(1L)))
            .thenReturn(List.of(stored(5011, SubtitleComparison.FULL_LINES, 0)));

        assertThat(forcedDue()).isFalse();
    }

    @Test
    void withDownloadsOffNothingIsPendingAndNothingIsRead() {
        assertThat(pendingWithDownloadsOff.full(Map.of(1L, IMDB))).isSameAs(Pending.NONE);
        assertThat(pendingWithDownloadsOff.forced(Map.of(1L, IMDB))).isSameAs(Pending.NONE);

        verifyNoInteractions(films, editions, sidecars, resolver, references, blocks, ledger);
    }
}
