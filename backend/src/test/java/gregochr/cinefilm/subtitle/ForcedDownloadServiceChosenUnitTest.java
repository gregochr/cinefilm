package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.ForcedDownloadService.ChosenOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/** Making a file the owner picked an edition's download: what refuses it, how it is pinned, and the cap. */
@ExtendWith(MockitoExtension.class)
class ForcedDownloadServiceChosenUnitTest {

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
    private ForcedNeedService need;

    @Mock
    private FullNeedService fullNeed;

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
        service = new ForcedDownloadService(films, editions, sidecars, resolver, need, fullNeed, references, blocks,
            ledger, lock, transactions);
        film = film(1L, "Taken", 2008, NOW);
        film.setImdbId(IMDB);
        edition = edition(film, 40L, "Original", 90 * MIN, null);
        MediaFile part = file(edition, "Taken.m4v");
        part.setId(10L);
        part.setPartNumber(1);
    }

    private void editionLoads() {
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of(edition));
    }

    private void rows(SubtitleKind kind, List<ForcedDownloadBlock> editionBlocks, List<SubtitleSidecar> found) {
        when(blocks.findByEditionIdInAndKind(List.of(40L), kind)).thenReturn(editionBlocks);
        when(sidecars.findByMediaFileIdInAndKind(List.of(10L), kind)).thenReturn(found);
    }

    private List<SubtitleSidecar> savedRows() {
        ArgumentCaptor<SubtitleSidecar> saved = ArgumentCaptor.forClass(SubtitleSidecar.class);
        verify(sidecars, atLeastOnce()).save(saved.capture());
        return saved.getAllValues();
    }

    private static OpenSubtitlesReference reference(long fileId, int cueCount) {
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

    private static ForcedDownloadBlock block(long fileId, SubtitleKind kind) {
        ForcedDownloadBlock block = new ForcedDownloadBlock();
        block.setEditionId(40L);
        block.setImdbId(IMDB);
        block.setOsFileId(fileId);
        block.setKind(kind);
        block.setRemovedAt(NOW);
        return block;
    }

    private static SubtitleSidecar download(long fileId, SubtitleKind kind) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setId(fileId);
        row.setMediaFileId(10L);
        row.setKind(kind);
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setOpenSubtitlesFileId(fileId);
        row.setPath("opensubtitles:40:" + fileId);
        row.setImdbId(IMDB);
        row.setCueCount(5);
        row.setPresence(Presence.PRESENT);
        return row;
    }

    /** {@code count} files used up on the edition: removed ones first, then downloads, ids from 7001 up. */
    private void used(SubtitleKind kind, int removed, int downloaded) {
        List<ForcedDownloadBlock> editionBlocks = new ArrayList<>();
        List<SubtitleSidecar> found = new ArrayList<>();
        for (int i = 0; i < removed; i++) {
            editionBlocks.add(block(7001L + i, kind));
        }
        for (int i = 0; i < downloaded; i++) {
            found.add(download(7501L + i, kind));
        }
        rows(kind, editionBlocks, found);
    }

    @Test
    void aFileIsAllowedWhenTheEditionHasUsedFewerThanTheCapAndNothingElseStopsIt() {
        editionLoads();
        used(SubtitleKind.FORCED, 1, 1);

        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).isEmpty();
        assertThat(service.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).isEqualTo(1);
    }

    @Test
    void theCapIsThreeFilesDownloadsAndRemovedOnesTogetherCheckedAtTwoThreeAndFour() {
        assertThat(DownloadPlan.MAX_DOWNLOADS).isEqualTo(3);
        editionLoads();

        used(SubtitleKind.FORCED, 1, 1);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).isEmpty();
        assertThat(service.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).isEqualTo(1);

        used(SubtitleKind.FORCED, 2, 1);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011))
            .contains(ChosenOutcome.CAP_REACHED);
        assertThat(service.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).isZero();

        used(SubtitleKind.FORCED, 2, 2);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011))
            .contains(ChosenOutcome.CAP_REACHED);
        assertThat(service.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).isZero();
    }

    @Test
    void aFileTheOwnerRemovedFromTheEditionIsBlockedForGood() {
        editionLoads();
        rows(SubtitleKind.FORCED, List.of(block(5011, SubtitleKind.FORCED)), List.of());

        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).contains(ChosenOutcome.BLOCKED);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5012)).isEmpty();
    }

    @Test
    void anEditionWithOnlyAPremadeRenditionTakesNoDownloadAndSaysNoFile() {
        edition.getMediaFiles().clear();
        MediaFile premade = file(edition, "Taken.720.mp4");
        premade.setKind(MediaKind.PREMADE_720);
        editionLoads();

        assertThat(service.hasOriginalFile(1L, 40L)).isFalse();
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).contains(ChosenOutcome.NO_FILE);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 6011)).contains(ChosenOutcome.NO_FILE);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.NO_FILE);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anEditionWithAnOriginalFileHasOneAndAGoneEditionHasNone() {
        editionLoads();
        assertThat(service.hasOriginalFile(1L, 40L)).isTrue();

        assertThat(service.hasOriginalFile(1L, 99L)).isFalse();
    }

    @Test
    void aFileRemovedWhileTheFilmHadAnotherImdbIdIsNotBlockedForTheCurrentOne() {
        editionLoads();
        ForcedDownloadBlock old = block(5011, SubtitleKind.FORCED);
        old.setImdbId("tt0000001");
        rows(SubtitleKind.FORCED, List.of(old), List.of());

        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).isEmpty();
    }

    @Test
    void aFileAlreadyDownloadedForTheEditionIsRefusedAsAlreadyDownloaded() {
        editionLoads();
        rows(SubtitleKind.FORCED, List.of(), List.of(download(5011, SubtitleKind.FORCED)));

        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011))
            .contains(ChosenOutcome.ALREADY_DOWNLOADED);
    }

    @Test
    void aBlockOrDownloadOfTheOtherKindNeverCountsEvenIfARepositoryReturnedIt() {
        editionLoads();
        rows(SubtitleKind.FULL, List.of(block(5011, SubtitleKind.FORCED), block(5012, SubtitleKind.FORCED)),
            List.of(download(5013, SubtitleKind.FORCED)));

        assertThat(service.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 5011)).isEmpty();
        assertThat(service.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 5013)).isEmpty();
        assertThat(service.downloadsLeft(1L, 40L, SubtitleKind.FULL, IMDB)).isEqualTo(3);
    }

    @Test
    void anEditionThatIsGoneOrWhoseFilmHasAnotherImdbIdIsChanged() {
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of());
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).contains(ChosenOutcome.CHANGED);
        assertThat(service.downloadsLeft(1L, 40L, SubtitleKind.FORCED, IMDB)).isZero();

        editionLoads();
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, "tt0000001", 5011))
            .contains(ChosenOutcome.CHANGED);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, null, 5011)).contains(ChosenOutcome.CHANGED);
    }

    @Test
    void aFilmTheOwnerMarkedAsNotNeedingThatKindRefusesOnlyThatKind() {
        film.setForcedNeedOwner(ForcedNeedChoice.NOT_NEEDED);
        editionLoads();
        rows(SubtitleKind.FULL, List.of(), List.of());

        assertThat(service.refusal(1L, 40L, SubtitleKind.FORCED, IMDB, 5011)).contains(ChosenOutcome.NOT_WANTED);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 5011)).isEmpty();

        film.setForcedNeedOwner(null);
        film.setFullNeedOwner(FullNeedChoice.NOT_NEEDED);
        assertThat(service.refusal(1L, 40L, SubtitleKind.FULL, IMDB, 5011)).contains(ChosenOutcome.NOT_WANTED);
    }

    @Test
    void aChosenForcedFileIsSavedAsTheEditionsDownloadAndPinnedWithTheOwnersCheck() {
        editionLoads();
        used(SubtitleKind.FORCED, 0, 0);

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.ADDED);

        ArgumentCaptor<SubtitleSidecar> saved = ArgumentCaptor.forClass(SubtitleSidecar.class);
        verify(sidecars).save(saved.capture());
        SubtitleSidecar row = saved.getValue();
        assertThat(row.getPath()).isEqualTo("opensubtitles:40:5011");
        assertThat(row.getKind()).isEqualTo(SubtitleKind.FORCED);
        assertThat(row.isDownload()).isTrue();
        assertThat(row.getMediaFileId()).isEqualTo(10L);
        assertThat(row.isWholeEdition()).isTrue();
        assertThat(row.getCueCount()).isEqualTo(12);
        assertThat(row.getImdbId()).isEqualTo(IMDB);
        assertThat(row.getDownloadedAt()).isEqualTo(NOW);
        // The pin is the owner's pick, not the owner's check: no "you checked it", and nothing that a nudge clears.
        assertThat(row.getOwnerChosenAt()).isEqualTo(NOW);
        assertThat(row.isOwnerChosen()).isTrue();
        assertThat(row.getOwnerVerifiedAt()).isNull();
        assertThat(row.getVerifiedParts()).isNull();
        assertThat(row.isVerifiedFor(List.of(10L))).isFalse();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void aChosenFullFileIsSavedAsAFullDownloadAndPinnedAndTheLedgerCountsIt() {
        editionLoads();
        used(SubtitleKind.FULL, 0, 0);

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FULL, reference(6011, SubtitleComparison.FULL_LINES),
            NOW)).isEqualTo(ChosenOutcome.ADDED);

        ArgumentCaptor<SubtitleSidecar> saved = ArgumentCaptor.forClass(SubtitleSidecar.class);
        verify(sidecars).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(SubtitleKind.FULL);
        assertThat(saved.getValue().getPath()).isEqualTo("opensubtitles:40:6011");
        assertThat(saved.getValue().getOwnerChosenAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getOwnerVerifiedAt()).isNull();
        ArgumentCaptor<ForcedCheckCandidate> tried = ArgumentCaptor.forClass(ForcedCheckCandidate.class);
        verify(ledger).save(tried.capture());
        assertThat(tried.getValue().getEditionId()).isEqualTo(40L);
        assertThat(tried.getValue().getImdbId()).isEqualTo(IMDB);
        assertThat(tried.getValue().getKind()).isEqualTo(SubtitleKind.FULL);
        assertThat(tried.getValue().getOsFileId()).isEqualTo(6011L);
        assertThat(tried.getValue().getTriedAt()).isEqualTo(NOW);
    }

    @Test
    void aNewPickTakesTheMarkFromTheEditionsEarlierPickOfTheSameKind() {
        editionLoads();
        SubtitleSidecar earlier = download(7501, SubtitleKind.FORCED);
        earlier.setOwnerChosenAt(NOW.minusSeconds(60));
        SubtitleSidecar ordinary = download(7502, SubtitleKind.FORCED);
        rows(SubtitleKind.FORCED, List.of(), List.of(earlier, ordinary));

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.ADDED);

        assertThat(earlier.isOwnerChosen()).isFalse();
        assertThat(ordinary.isOwnerChosen()).isFalse();
        // The cleared row is saved explicitly (not left to dirty checking), then the new pick.
        assertThat(savedRows()).hasSize(2).first().isSameAs(earlier);
        assertThat(savedRows().get(1).getOpenSubtitlesFileId()).isEqualTo(5011L);
        assertThat(savedRows().get(1).isOwnerChosen()).isTrue();
    }

    @Test
    void aPickMadeForAnotherEditionThatSharesAPartFileKeepsItsMark() {
        editionLoads();
        SubtitleSidecar otherEditions = download(7501, SubtitleKind.FORCED);
        otherEditions.setPath("opensubtitles:99:7501");
        otherEditions.setOwnerChosenAt(NOW.minusSeconds(60));
        rows(SubtitleKind.FORCED, List.of(), List.of(otherEditions));

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.ADDED);

        assertThat(otherEditions.getOwnerChosenAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(savedRows()).singleElement().satisfies(row -> assertThat(row.getOpenSubtitlesFileId())
            .isEqualTo(5011L));
    }

    @Test
    void aFileAlreadyOnTheLedgerIsNotPutThereTwice() {
        editionLoads();
        used(SubtitleKind.FORCED, 0, 0);
        when(ledger.existsByEditionIdAndImdbIdAndOsFileIdAndKind(40L, IMDB, 5011, SubtitleKind.FORCED))
            .thenReturn(true);

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.ADDED);

        assertThat(savedRows()).singleElement().satisfies(row -> assertThat(row.getOpenSubtitlesFileId())
            .isEqualTo(5011L));
    }

    @Test
    void aForcedFileOfThreeHundredLinesAndAFullFileOfTwoHundredNinetyNineAreNotUsable() {
        editionLoads();
        used(SubtitleKind.FORCED, 0, 0);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED,
            reference(5011, SubtitleComparison.FULL_LINES), NOW)).isEqualTo(ChosenOutcome.UNUSABLE);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED,
            reference(5011, SubtitleComparison.FULL_LINES - 1), NOW)).isEqualTo(ChosenOutcome.ADDED);

        used(SubtitleKind.FULL, 0, 0);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FULL,
            reference(6011, SubtitleComparison.FULL_LINES - 1), NOW)).isEqualTo(ChosenOutcome.UNUSABLE);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FULL,
            reference(6011, SubtitleComparison.FULL_LINES), NOW)).isEqualTo(ChosenOutcome.ADDED);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5012, 0), NOW))
            .isEqualTo(ChosenOutcome.UNUSABLE);
    }

    @Test
    void anUnusableFileIsNotSavedAnywhere() {
        editionLoads();
        used(SubtitleKind.FORCED, 0, 0);

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 0), NOW))
            .isEqualTo(ChosenOutcome.UNUSABLE);

    }

    @Test
    void theCapIsRecheckedUnderTheLockSoAFullEditionTakesNoMore() {
        editionLoads();
        used(SubtitleKind.FORCED, 2, 1);

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.CAP_REACHED);

        assertThat(lock.holder()).isNull();
    }

    @Test
    void theOneBeforeTheCapStillTakesTheFile() {
        editionLoads();
        used(SubtitleKind.FORCED, 1, 1);

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.ADDED);
    }

    @Test
    void theRefusalsAreRecheckedUnderTheLock() {
        editionLoads();
        when(blocks.findByEditionIdInAndKind(List.of(40L), SubtitleKind.FORCED))
            .thenReturn(List.of(block(5011, SubtitleKind.FORCED)));
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.BLOCKED);

        film.setForcedNeedOwner(ForcedNeedChoice.NOT_NEEDED);
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5012, 12), NOW))
            .isEqualTo(ChosenOutcome.NOT_WANTED);

        OpenSubtitlesReference other = reference(5013, 12);
        other.setImdbId("tt0000001");
        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, other, NOW)).isEqualTo(ChosenOutcome.CHANGED);
    }

    @Test
    void anAddIsBusyWhileAScanHoldsTheLockAndLeavesTheScansLockAlone() {
        assertThat(lock.tryAcquire(LibraryLock.SCAN)).isTrue();

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.BUSY);

        assertThat(lock.holder()).isEqualTo(LibraryLock.SCAN);
    }

    @Test
    void anAddForAGoneEditionIsChangedAndGivesTheLockBack() {
        when(editions.findWithFilesByFilmIdIn(List.of(1L))).thenReturn(List.of());

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.CHANGED);

        assertThat(lock.holder()).isNull();
    }

    @Test
    void anAddThatLosesTheRaceOnTheUniquePathIsAlreadyDownloadedNotAFailure() {
        editionLoads();
        used(SubtitleKind.FORCED, 0, 0);
        when(sidecars.save(argThat(row -> "opensubtitles:40:5011".equals(row.getPath()))))
            .thenThrow(new DataIntegrityViolationException("dup"));

        assertThat(service.addChosen(1L, 40L, SubtitleKind.FORCED, reference(5011, 12), NOW))
            .isEqualTo(ChosenOutcome.ALREADY_DOWNLOADED);

        assertThat(lock.holder()).isNull();
    }
}
