package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedChoice.DownloadSide;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.ForcedChoice.Input;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.CountsDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import gregochr.cinefilm.subtitle.ForcedResolver.DownloadState;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/** The download service's rules with the repositories and the resolver faked. */
@ExtendWith(MockitoExtension.class)
class ForcedDownloadServiceUnitTest {

    private static final String IMDB = "tt0936501";
    private static final String CUES = "1\n00:00:10,000 --> 00:00:12,000\nHello\n\n";
    private static final Assessment UNCHECKED = new Assessment(Trust.UNCHECKED, Proof.NONE);
    private static final Assessment FAILED = new Assessment(Trust.FAILED, Proof.NONE);

    @Mock
    private FilmRepository films;

    @Mock
    private EditionRepository editions;

    @Mock
    private SubtitleSidecarRepository sidecars;

    @Mock
    private ForcedResolver resolver;

    @Mock
    private ForcedNeedService need;

    @Mock
    private OpenSubtitlesReferenceRepository references;

    @Mock
    private ForcedDownloadBlockRepository blocks;

    @Mock
    private ForcedCheckCandidateRepository ledger;

    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();
    private ForcedDownloadService service;
    private Film film;
    private Edition edition;

    @BeforeEach
    void setUp() {
        service = new ForcedDownloadService(films, editions, sidecars, resolver, need, references, blocks, ledger,
            lock, transactions);
        film = film(1L, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        film.setOsCandidates(OpenSubtitlesResult.listToJson(List.of(candidate(5011), candidate(5012))));
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        MediaFile part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setPartNumber(1);
    }

    private static OpenSubtitlesResult candidate(long fileId) {
        return new OpenSubtitlesResult(fileId - 1000, fileId, "Release " + fileId, 23.976, 10, true, false, null,
            null, 1);
    }

    private static OpenSubtitlesReference stored(long fileId, int cueCount) {
        OpenSubtitlesReference reference = new OpenSubtitlesReference();
        reference.setId(fileId);
        reference.setFilmId(1L);
        reference.setImdbId(IMDB);
        reference.setOsFileId(fileId);
        reference.setRelease("Release " + fileId);
        reference.setCuesText(CUES);
        reference.setCueCount(cueCount);
        reference.setFetchedAt(NOW);
        return reference;
    }

    private static SubtitleSidecar owner() {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(5L);
        row.setMediaFileId(10L);
        row.setCuesText(CUES);
        row.setCueCount(1);
        row.setUpdatedAt(NOW);
        return row;
    }

    private static SubtitleSidecar download(long fileId) {
        SubtitleSidecar row = owner();
        row.setId(fileId);
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setWholeEdition(true);
        row.setOpenSubtitlesFileId(fileId);
        row.setPath("opensubtitles:40:" + fileId);
        row.setImdbId(IMDB);
        return row;
    }

    /** What the resolver says about the edition: an owner file in a state (or none), downloads, the embedded track. */
    private Resolution resolution(Assessment file, boolean conflict, boolean embedded, Assessment... downloads) {
        SubtitleSidecar owner = owner();
        List<Slot> slots = file == null || conflict ? List.of() : List.of(new Slot(owner, 0));
        Pick pick = new Pick(slots, conflict ? Set.of(5L, 6L) : Set.of());
        List<DownloadState> states = new ArrayList<>();
        List<DownloadSide> sides = new ArrayList<>();
        for (int i = 0; i < downloads.length; i++) {
            SubtitleSidecar d = download(5011L + i);
            states.add(new DownloadState(d, downloads[i], null, null, "dv" + i));
            sides.add(new DownloadSide(d.getId(), downloads[i]));
        }
        FileSide side = file == null || conflict ? null : new FileSide(file, 1);
        return new Resolution(edition, List.of(10L), pick, slots.isEmpty() ? null : "fv",
            file == null || conflict ? null : file, null, null, Map.of(), states, embedded,
            ForcedChoice.decide(new Input(side, sides, embedded)), Map.of());
    }

    private void needed(boolean isNeeded) {
        FilmNeedDto dto = new FilmNeedDto(1L, "Taken", 2008, isNeeded ? "NEEDED" : "UNSURE", "OPENSUBTITLES", null,
            "en", List.of("en"), false, 2, NOW);
        when(need.list()).thenReturn(new NeedListDto(List.of(dto), new CountsDto(1, 1, 0, 0, 0)));
    }

    private void searched() {
        when(films.findOpenSubtitlesBest()).thenReturn(List.of(new OsBestRef(1L, IMDB, candidate(5011).toJson(),
            film.getOsCandidates())));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
    }

    private void resolves(Resolution resolution) {
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution));
    }

    @Test
    void nothingIsDueWhenNoFilmNeedsSubtitlesAndTheResolverIsNeverAsked() {
        needed(false);
        when(films.findOpenSubtitlesBest()).thenReturn(List.of());

        assertThat(service.due()).isEmpty();
        verifyNoInteractions(resolver);
    }

    @Test
    void aNeededFilmWithNoSearchResultsIsNotDue() {
        needed(true);
        when(films.findOpenSubtitlesBest()).thenReturn(List.of());

        assertThat(service.due()).isEmpty();
    }

    @Test
    void aNeededEditionWithNothingThatPlaysIsDueAMissingDownloadWithTheFilmsCandidates() {
        needed(true);
        searched();
        resolves(resolution(null, false, false));

        List<DueDownload> due = service.due();

        assertThat(due).hasSize(1);
        assertThat(due.get(0).kind()).isEqualTo(Kind.MISSING);
        assertThat(due.get(0).candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5012L);
        assertThat(due.get(0).remainingFetches()).isEqualTo(3);
        assertThat(due.get(0).promotion()).isNull();
    }

    @Test
    void anEditionWhoseOwnerFileFailedIsDueAReplacementPinnedToTheFilesVersion() {
        needed(true);
        searched();
        resolves(resolution(FAILED, false, false));

        List<DueDownload> due = service.due();

        assertThat(due).extracting(DueDownload::kind).containsExactly(Kind.REPLACEMENT);
        assertThat(due.get(0).fileVersion()).isEqualTo("fv");
    }

    @Test
    void conflictsEmbeddedTracksAndDownloadsThatHaveNotFailedLeaveNothingDue() {
        needed(true);
        searched();

        resolves(resolution(null, true, false));
        assertThat(service.due()).isEmpty();
        resolves(resolution(FAILED, false, true));
        assertThat(service.due()).isEmpty();
        resolves(resolution(null, false, false, UNCHECKED));
        assertThat(service.due()).isEmpty();
    }

    @Test
    void aFilmWhoseCandidatesWereNeverStoredIsNotDue() {
        needed(true);
        when(films.findOpenSubtitlesBest()).thenReturn(List.of(new OsBestRef(1L, IMDB, candidate(5011).toJson(),
            null)));
        when(editions.findWithFilesByFilmIdIn(Set.of(1L))).thenReturn(List.of(edition));
        resolves(resolution(null, false, false));

        assertThat(service.due()).isEmpty();
    }

    @Test
    void storedReferencesBlocksAndDownloadsShapeTheCandidatesAndTheCap() {
        needed(true);
        searched();
        resolves(resolution(null, false, false, FAILED));
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(40L);
        block.setImdbId(IMDB);
        block.setOsFileId(5012);
        block.setRemovedAt(NOW);
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(block));
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED))
            .thenReturn(List.of(download(5011)));

        // 5011 is a (failed) download and 5012 was removed: no candidate is left, so nothing is due.
        assertThat(service.due()).isEmpty();
    }

    @Test
    void aFullBlockOrAFullDownloadNeverUsesUpAForcedCandidateEvenIfARepositoryReturnedThem() {
        needed(true);
        searched();
        resolves(resolution(null, false, false, FAILED));
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setKind(SubtitleKind.FULL);
        block.setEditionId(40L);
        block.setImdbId(IMDB);
        block.setOsFileId(5012);
        block.setRemovedAt(NOW);
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(block));
        SubtitleSidecar fullDownload = download(5011);
        fullDownload.setKind(SubtitleKind.FULL);
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED))
            .thenReturn(List.of(fullDownload));

        assertThat(service.due()).singleElement().satisfies(due -> {
            assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5011L, 5012L);
            assertThat(due.remainingFetches()).isEqualTo(3);
        });
    }

    @Test
    void aStoredFittingReferenceIsPromotedForAReplacement() {
        needed(true);
        searched();
        Resolution failed = resolution(FAILED, false, false);
        Resolution withKept = new Resolution(failed.edition(), failed.partIds(), failed.filePick(),
            failed.fileVersion(), failed.fileAssessment(), null, 5011L, Map.of(), List.of(), false,
            failed.decision(), Map.of());
        resolves(withKept);
        when(references.findByFilmIdIn(Set.of(1L))).thenReturn(List.of(stored(5011, 1)));
        ForcedCheckCandidate compared = new ForcedCheckCandidate();
        compared.setEditionId(40L);
        compared.setImdbId(IMDB);
        compared.setOsFileId(5011);
        compared.setTriedAt(NOW);
        when(ledger.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of(compared));

        DueDownload due = service.due().get(0);

        assertThat(due.promotion()).isNotNull();
        assertThat(due.promotion().getOsFileId()).isEqualTo(5011L);
        assertThat(due.candidates()).extracting(OpenSubtitlesResult::fileId).containsExactly(5012L);
    }

    @Test
    void anAddIsRefusedWithTheLockTakenAndTheOneItDidTakeIsAlwaysGivenBack() {
        DueDownload due = new DueDownload(Kind.MISSING, 1L, 40L, IMDB, "Original", null, List.of(), 3, null);
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        assertThat(service.add(due, stored(5011, 1), NOW)).isEqualTo(AddOutcome.BUSY);
        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
        lock.release();

        when(films.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.add(due, stored(5011, 1), NOW)).isEqualTo(AddOutcome.DROPPED);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anAddThatLosesTheRaceOnTheUniquePathIsDroppedNotFailed() {
        DueDownload due = new DueDownload(Kind.MISSING, 1L, 40L, IMDB, "Original", null, List.of(candidate(5011)), 3,
            null);
        when(films.findById(1L)).thenReturn(Optional.of(film));
        when(need.isNeeded(1L)).thenReturn(true);
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of(edition));
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED)).thenReturn(List.of());
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), SubtitleKind.FORCED)).thenReturn(List.of());
        when(resolver.resolve(List.of(edition))).thenReturn(Map.of(40L, resolution(null, false, false)));
        when(sidecars.save(argThat(row -> row.isDownload()
            && row.getPath().equals("opensubtitles:40:5011")))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThat(service.add(due, stored(5011, 1), NOW)).isEqualTo(AddOutcome.DROPPED);
        assertThat(lock.holder()).isNull();
        verify(sidecars).save(argThat(row -> row.getOpenSubtitlesFileId() == 5011));
    }
}
