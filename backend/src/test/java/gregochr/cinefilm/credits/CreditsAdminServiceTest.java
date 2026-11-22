package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsList;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsRow;
import gregochr.cinefilm.credits.CreditsApply.PlexState;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.FilmEditionCount;
import gregochr.cinefilm.film.EditionRepository.SourceCount;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

/** The list's mapping: counts, the page's rows, Plex's state and the size cap, over mocked repositories. */
@ExtendWith(MockitoExtension.class)
class CreditsAdminServiceTest {

    private static final long FILE_BYTES = 4_000_000_000L;
    private static final String FINGERPRINT = FILE_BYTES + ":" + NOW.toEpochMilli();

    @Mock
    private EditionRepository editions;
    @Mock
    private PlexCreditsMarkerRepository markers;

    @Mock
    private PlatformTransactionManager transactions;

    private final LibraryLock lock = new LibraryLock();

    private CreditsAdminService service() {
        return new CreditsAdminService(editions, markers, lock, transactions);
    }

    /** A film (id = n) with one 100-minute edition (id = n) whose one file (id = n) has a fingerprint. */
    private static Edition seeded(long n, String title, Integer year, Long marker, CreditsMarkerSource source) {
        Film film = film(n, title, year, NOW);
        Edition edition = edition(film, n, "Original", 100 * MIN, marker);
        edition.setCreditsMarkerSource(source);
        MediaFile file = file(edition, title + ".m4v");
        file.setId(n);
        file.setSizeBytes(FILE_BYTES);
        file.setModifiedAt(NOW);
        return edition;
    }

    private static PlexCreditsMarker plexRow(long fileId, String fingerprint, long startMs) {
        PlexCreditsMarker row = new PlexCreditsMarker();
        row.setMediaFileId(fileId);
        row.setFingerprint(fingerprint);
        row.setStartMs(startMs);
        row.setImportedAt(NOW);
        return row;
    }

    /** Answers the first page of 25 for the filter with these editions, and loads exactly their ids. */
    private void page(CreditsFilter filter, List<Edition> page) {
        List<Long> ids = page.stream().map(Edition::getId).toList();
        when(editions.findCreditsPageIds(filter.anySource(), filter.unsettled(), filter.source(),
            PageRequest.of(0, 25))).thenReturn(ids);
        when(editions.findWithFilesAndFilmByIdIn(ids)).thenReturn(page);
    }

    @Test
    void theFourCountsAddUpAndNoSourceCountsAMarkerNotSettledYet() {
        when(editions.countVisibleBySource()).thenReturn(List.of(new SourceCount(null, 5),
            new SourceCount(CreditsMarkerSource.PLEX, 3), new SourceCount(CreditsMarkerSource.OWNER, 2),
            new SourceCount(CreditsMarkerSource.AUTO, 1)));
        when(editions.findCreditsPageIds(false, false, CreditsMarkerSource.PLEX, PageRequest.of(0, 25)))
            .thenReturn(List.of());

        CreditsList list = service().list(CreditsFilter.PLEX, 0, 25);

        assertThat(list.counts()).isEqualTo(new CreditsAdminDtos.Counts(5, 3, 2, 11));
        assertThat(list.total()).isEqualTo(3);
        assertThat(list.items()).isEmpty();
    }

    @Test
    void theTotalIsTheChosenFiltersCount() {
        when(editions.countVisibleBySource()).thenReturn(List.of(new SourceCount(null, 5),
            new SourceCount(CreditsMarkerSource.PLEX, 3), new SourceCount(CreditsMarkerSource.OWNER, 2)));
        when(editions.findCreditsPageIds(false, true, CreditsMarkerSource.PLEX, PageRequest.of(0, 25)))
            .thenReturn(List.of());
        when(editions.findCreditsPageIds(false, false, CreditsMarkerSource.OWNER, PageRequest.of(0, 25)))
            .thenReturn(List.of());
        when(editions.findCreditsPageIds(true, false, CreditsMarkerSource.PLEX, PageRequest.of(0, 25)))
            .thenReturn(List.of());

        assertThat(service().list(CreditsFilter.NONE, 0, 25).total()).isEqualTo(5);
        assertThat(service().list(CreditsFilter.OWNER, 0, 25).total()).isEqualTo(2);
        assertThat(service().list(CreditsFilter.ALL, 0, 25).total()).isEqualTo(10);
    }

    @Test
    void aRowCarriesTheFilmTheEditionTheCountTheRuntimeAndPlexsState() {
        Edition edition = seeded(7, "Heat", 1995, 5_000_000L, CreditsMarkerSource.PLEX);
        page(CreditsFilter.PLEX, List.of(edition));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(List.of(7L))).thenReturn(List.of(new FilmEditionCount(7L, 2)));
        when(markers.findAllById(List.of(7L))).thenReturn(List.of(plexRow(7, FINGERPRINT, 5_000_000)));

        List<CreditsRow> items = service().list(CreditsFilter.PLEX, 0, 25).items();

        assertThat(items).containsExactly(new CreditsRow(7, 7, "Heat", 1995, "Original", 2, 100 * MIN, 5_000_000L,
            CreditsMarkerSource.PLEX, new CreditsAdminDtos.Plex(PlexState.USABLE, 5_000_000L)));
    }

    @Test
    void plexsStateIsComputedWhateverTheEditionsOwnSourceIs() {
        Edition owner = seeded(1, "Owner", null, 5_500_000L, CreditsMarkerSource.OWNER);
        Edition refused = seeded(2, "Refused", 2001, null, null);
        Edition stale = seeded(3, "Stale", 2002, null, null);
        Edition none = seeded(4, "None", 2003, null, null);
        page(CreditsFilter.ALL, List.of(owner, refused, stale, none));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(List.of(1L, 2L, 3L, 4L))).thenReturn(List.of());
        when(markers.findAllById(List.of(1L, 2L, 3L, 4L))).thenReturn(List.of(plexRow(1, FINGERPRINT, 5_000_000),
            plexRow(2, FINGERPRINT, 1_000_000), plexRow(3, "1:2", 5_000_000)));

        List<CreditsRow> items = service().list(CreditsFilter.ALL, 0, 25).items();

        assertThat(items).extracting(r -> r.plex().state()).containsExactly(PlexState.USABLE, PlexState.REFUSED,
            PlexState.STALE, PlexState.NONE);
        assertThat(items).extracting(r -> r.plex().markerMs()).containsExactly(5_000_000L, 1_000_000L, null, null);
        assertThat(items.get(0).markerMs()).isEqualTo(5_500_000L);
        assertThat(items.get(0).source()).isEqualTo(CreditsMarkerSource.OWNER);
        assertThat(items.get(0).year()).isNull();
    }

    @Test
    void anEditionWithNoTimelineHasNoRuntimeAndAnEditionWithNoFileHasNoPlexRow() {
        Edition noDuration = seeded(1, "No Duration", 2000, null, null);
        MediaFile part = noDuration.originalParts().get(0);
        part.setDurationMs(null);
        part.setVideoDurationMs(null);
        Edition noFile = seeded(2, "No File", 2000, null, null);
        noFile.getMediaFiles().clear();
        page(CreditsFilter.ALL, List.of(noDuration, noFile));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(List.of(1L, 2L))).thenReturn(List.of());
        when(markers.findAllById(List.of(1L))).thenReturn(List.of(plexRow(1, FINGERPRINT, 5_000_000)));

        List<CreditsRow> items = service().list(CreditsFilter.ALL, 0, 25).items();

        assertThat(items.get(0).runtimeMs()).isNull();
        assertThat(items.get(0).plex().state()).isEqualTo(PlexState.NO_TIMELINE);
        assertThat(items.get(1).runtimeMs()).isNull();
        assertThat(items.get(1).plex().state()).isEqualTo(PlexState.NONE);
        assertThat(items.get(1).editionCount()).isZero();
    }

    @Test
    void aPageOfOnlyFilelessEditionsShowsEveryRowAsPlexNone() {
        Edition noFile = seeded(2, "No File", 2000, null, null);
        noFile.getMediaFiles().clear();
        page(CreditsFilter.ALL, List.of(noFile));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(List.of(2L))).thenReturn(List.of());

        List<CreditsRow> items = service().list(CreditsFilter.ALL, 0, 25).items();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).plex().state()).isEqualTo(PlexState.NONE);
    }

    @Test
    void theRowsKeepTheOrderOfThePageOfIdsNotTheOrderTheEditionsLoadIn() {
        Edition first = seeded(1, "Alpha", 2000, null, null);
        Edition second = seeded(2, "Beta", 2000, null, null);
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.findCreditsPageIds(true, false, CreditsMarkerSource.PLEX, PageRequest.of(0, 25)))
            .thenReturn(List.of(1L, 2L));
        when(editions.findWithFilesAndFilmByIdIn(List.of(1L, 2L))).thenReturn(List.of(second, first));
        when(editions.countVisibleByFilmIdIn(List.of(1L, 2L))).thenReturn(List.of());
        when(markers.findAllById(List.of(1L, 2L))).thenReturn(List.of());

        assertThat(service().list(CreditsFilter.ALL, 0, 25).items()).extracting(CreditsRow::title)
            .containsExactly("Alpha", "Beta");
    }

    @Test
    void aSizeAboveTheCapIsCappedAtOneHundredAndEchoedAsSuch() {
        when(editions.countVisibleBySource()).thenReturn(List.of());
        List<Edition> many = LongStream.rangeClosed(1, 100).mapToObj(n -> seeded(n, "Film " + n, 2000, null, null))
            .toList();
        // Only a request for page 2 of exactly 100 rows is answered; any other page size finds nothing.
        List<Long> ids = many.stream().map(Edition::getId).toList();
        when(editions.findCreditsPageIds(true, false, CreditsMarkerSource.PLEX, PageRequest.of(2, 100)))
            .thenReturn(ids);
        when(editions.findWithFilesAndFilmByIdIn(ids)).thenReturn(many);
        when(editions.countVisibleByFilmIdIn(ids)).thenReturn(List.of());
        when(markers.findAllById(ids)).thenReturn(List.of());

        CreditsList list = service().list(CreditsFilter.ALL, 2, 5000);

        assertThat(list.size()).isEqualTo(100);
        assertThat(list.page()).isEqualTo(2);
        assertThat(list.items()).hasSize(100);
    }

    @Test
    void aNegativePageAndASizeBelowOneAreClamped() {
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.findCreditsPageIds(true, false, CreditsMarkerSource.PLEX, PageRequest.of(0, 1)))
            .thenReturn(List.of());

        CreditsList list = service().list(CreditsFilter.ALL, -3, 0);

        assertThat(list.page()).isZero();
        assertThat(list.size()).isEqualTo(1);
    }

    // ---- the two writes ----

    private static final long MARKER = 5_000_000L;

    /** A seeded edition (id = film = file = n) whose one part is {@code runtimeMs} long. */
    private static Edition ofLength(long n, long runtimeMs, Long marker, CreditsMarkerSource source) {
        Edition edition = seeded(n, "Film " + n, 2000, marker, source);
        MediaFile part = edition.originalParts().get(0);
        part.setDurationMs(runtimeMs);
        part.setVideoDurationMs(runtimeMs);
        return edition;
    }

    /** Lets the write find the edition and the answer be built from it, with Plex's row (when given). */
    private void stubWriteAndAnswer(Edition edition, PlexCreditsMarker plex) {
        long id = edition.getId();
        when(editions.findWithFilesAndFilmByIdIn(List.of(id))).thenReturn(List.of(edition));
        when(editions.countVisibleByFilmIdIn(List.of(id))).thenReturn(List.of(new FilmEditionCount(id, 1)));
        when(markers.findAllById(List.of(id))).thenReturn(plex == null ? List.of() : List.of(plex));
    }

    @ParameterizedTest
    @CsvSource({
        // total, one second below the lower bound, the first second inside, one second above it
        "7200500, 4320000, 4321000, 4322000",
        "6000000, 3599000, 3600000, 3601000"})
    void aTimeIsRefusedOneSecondBelowTheLowerBoundAndSetAtAndAboveIt(long total, long below, long at, long above) {
        Edition edition = ofLength(7, total, null, null);
        stubWriteAndAnswer(edition, null);

        assertThatThrownBy(() -> service().set(7, below)).isInstanceOf(BadRequestException.class)
            .hasMessage("That time is outside where end credits can start");
        assertThat(edition.getCreditsMarkerMs()).isNull();
        assertThat(service().set(7, at).markerMs()).isEqualTo(at);
        assertThat(edition.getCreditsMarkerMs()).isEqualTo(at);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.OWNER);
        assertThat(service().set(7, above).markerMs()).isEqualTo(above);
        assertThat(edition.getCreditsMarkerMs()).isEqualTo(above);
    }

    @ParameterizedTest
    @CsvSource({
        // total, the last whole second at least a minute before the end, the next one
        "7200500, 7140000, 7141000",
        "6000000, 5940000, 5941000"})
    void aTimeIsSetAtTheUpperBoundAndRefusedOneSecondAfterIt(long total, long at, long after) {
        Edition edition = ofLength(7, total, null, null);
        stubWriteAndAnswer(edition, null);

        assertThat(service().set(7, at).markerMs()).isEqualTo(at);
        assertThatThrownBy(() -> service().set(7, after)).isInstanceOf(BadRequestException.class)
            .hasMessage("That time is outside where end credits can start");
        assertThat(edition.getCreditsMarkerMs()).isEqualTo(at);
    }

    @Test
    void aMissingTimeIsInvalidRequestOnceTheEditionIsKnown() {
        Edition edition = ofLength(7, 6_000_000, null, null);
        when(editions.findWithFilesAndFilmByIdIn(List.of(7L))).thenReturn(List.of(edition));

        assertThatThrownBy(() -> service().set(7, null)).isInstanceOf(BadRequestException.class)
            .hasMessage("Invalid request");
        assertThat(edition.getCreditsMarkerMs()).isNull();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anUnknownOrHiddenEditionIs404BeforeAMissingTimeOrABadOneIsLookedAt() {
        Edition hidden = seeded(8, "Hidden", 2000, null, null);
        hidden.originalParts().get(0).setPresence(Presence.MISSING);
        when(editions.findWithFilesAndFilmByIdIn(List.of(7L))).thenReturn(List.of());
        when(editions.findWithFilesAndFilmByIdIn(List.of(8L))).thenReturn(List.of(hidden));

        assertThatThrownBy(() -> service().set(7, null)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThatThrownBy(() -> service().set(8, null)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThatThrownBy(() -> service().set(7, 1_000L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
    }

    @Test
    void anEditionWithNoTimelineSaysItsLengthIsNotKnown() {
        Edition edition = seeded(7, "No Length", 2000, null, null);
        MediaFile part = edition.originalParts().get(0);
        part.setDurationMs(null);
        part.setVideoDurationMs(null);
        when(editions.findWithFilesAndFilmByIdIn(List.of(7L))).thenReturn(List.of(edition));

        assertThatThrownBy(() -> service().set(7, 5_000_000L)).isInstanceOf(BadRequestException.class)
            .hasMessage("This film's length isn't known");
        assertThat(edition.getCreditsMarkerMs()).isNull();
    }

    @Test
    void anUnknownEditionIs404ForBothWritesAndSoIsAHiddenOne() {
        Edition hidden = seeded(8, "Hidden", 2000, null, null);
        hidden.originalParts().get(0).setPresence(Presence.MISSING);
        when(editions.findWithFilesAndFilmByIdIn(List.of(7L))).thenReturn(List.of());
        when(editions.findWithFilesAndFilmByIdIn(List.of(8L))).thenReturn(List.of(hidden));

        assertThatThrownBy(() -> service().set(7, MARKER)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThatThrownBy(() -> service().clear(7)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThatThrownBy(() -> service().set(8, MARKER)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThatThrownBy(() -> service().clear(8)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such edition");
        assertThat(hidden.getCreditsMarkerMs()).isNull();
        assertThat(lock.holder()).isNull();
    }

    @Test
    void anOwnerTimeReplacesAPlexMarkerAndTheAnswerIsTheNewRow() {
        Edition edition = seeded(7, "Heat", 1995, MARKER, CreditsMarkerSource.PLEX);
        stubWriteAndAnswer(edition, plexRow(7, FINGERPRINT, MARKER));

        CreditsRow row = service().set(7, 5_500_000L);

        assertThat(edition.getCreditsMarkerMs()).isEqualTo(5_500_000L);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.OWNER);
        assertThat(row).isEqualTo(new CreditsRow(7, 7, "Heat", 1995, "Original", 1, 100 * MIN, 5_500_000L,
            CreditsMarkerSource.OWNER, new CreditsAdminDtos.Plex(PlexState.USABLE, MARKER)));
        verify(editions).save(edition);
    }

    @Test
    void clearingAnOwnerTimeGoesBackToAUsablePlexMarker() {
        Edition edition = seeded(7, "Heat", 1995, 5_500_000L, CreditsMarkerSource.OWNER);
        stubWriteAndAnswer(edition, plexRow(7, FINGERPRINT, MARKER));

        CreditsRow row = service().clear(7);

        assertThat(edition.getCreditsMarkerMs()).isEqualTo(MARKER);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(row.markerMs()).isEqualTo(MARKER);
        assertThat(row.source()).isEqualTo(CreditsMarkerSource.PLEX);
        verify(editions).save(edition);
    }

    @Test
    void clearingAnOwnerTimeWithNoUsablePlexMarkerLeavesNoMarkerAndNoSource() {
        Edition edition = seeded(7, "Heat", 1995, 5_500_000L, CreditsMarkerSource.OWNER);
        stubWriteAndAnswer(edition, plexRow(7, "1:2", MARKER));

        CreditsRow row = service().clear(7);

        assertThat(edition.getCreditsMarkerMs()).isNull();
        assertThat(edition.getCreditsMarkerSource()).isNull();
        assertThat(row.markerMs()).isNull();
        assertThat(row.source()).isNull();
        assertThat(row.plex().state()).isEqualTo(PlexState.STALE);
    }

    @Test
    void clearingAnEditionThatIsNotOwnersLeavesAStalePlexMarkerForThePass() {
        Edition edition = seeded(7, "Heat", 1995, MARKER, CreditsMarkerSource.PLEX);
        stubWriteAndAnswer(edition, plexRow(7, "1:2", MARKER));

        CreditsRow row = service().clear(7);

        assertThat(edition.getCreditsMarkerMs()).isEqualTo(MARKER);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.PLEX);
        assertThat(row.markerMs()).isEqualTo(MARKER);
        assertThat(row.plex().state()).isEqualTo(PlexState.STALE);
    }

    @Test
    void bothWritesAreRefusedWithTheHoldersTextAndWriteNothingWhileTheLockIsHeld() {
        Edition edition = seeded(7, "Heat", 1995, 5_500_000L, CreditsMarkerSource.OWNER);
        lock.tryAcquire(LibraryLock.SCAN);

        assertThatThrownBy(() -> service().set(7, MARKER)).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");
        assertThatThrownBy(() -> service().clear(7)).isInstanceOf(ConflictException.class)
            .hasMessage("A scan is running");

        assertThat(edition.getCreditsMarkerMs()).isEqualTo(5_500_000L);
        assertThat(edition.getCreditsMarkerSource()).isEqualTo(CreditsMarkerSource.OWNER);
        assertThat(lock.heldBy(LibraryLock.SCAN)).as("the other holder's lock is not released").isTrue();
    }

    @Test
    void theLockIsReleasedWhenAWriteFails() {
        when(editions.findWithFilesAndFilmByIdIn(List.of(7L))).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> service().set(7, MARKER)).isInstanceOf(IllegalStateException.class);
        assertThat(lock.holder()).isNull();
        assertThatThrownBy(() -> service().clear(7)).isInstanceOf(IllegalStateException.class);
        assertThat(lock.holder()).isNull();
    }

    @Test
    void theLockIsStillHeldWhenTheWriteCommitsAndFreeWhenTheAnswerIsRead() {
        Edition edition = seeded(7, "Heat", 1995, null, null);
        stubWriteAndAnswer(edition, null);
        List<Boolean> heldAtCommit = new ArrayList<>();
        doAnswer(invocation -> heldAtCommit.add(lock.heldBy(LibraryLock.CREDITS))).when(transactions).commit(null);

        service().set(7, MARKER);

        // The write's commit happens under the lock; the answer's read transaction commits after it is given back.
        assertThat(heldAtCommit).containsExactly(true, false);
        assertThat(lock.holder()).isNull();
    }
}
