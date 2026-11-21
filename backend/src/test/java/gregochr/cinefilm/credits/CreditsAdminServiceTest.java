package gregochr.cinefilm.credits;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** The list's mapping: counts, the page's rows, Plex's state and the size cap, over mocked repositories. */
@ExtendWith(MockitoExtension.class)
class CreditsAdminServiceTest {

    private static final long FILE_BYTES = 4_000_000_000L;
    private static final String FINGERPRINT = FILE_BYTES + ":" + NOW.toEpochMilli();

    @Mock
    private EditionRepository editions;
    @Mock
    private PlexCreditsMarkerRepository markers;

    private CreditsAdminService service() {
        return new CreditsAdminService(editions, markers);
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

    private void page(List<Edition> page) {
        when(editions.findCreditsPageIds(anyBoolean(), anyBoolean(), eq(CreditsMarkerSource.PLEX),
            any(Pageable.class))).thenReturn(page.stream().map(Edition::getId).toList());
        when(editions.findWithFilesAndFilmByIdIn(anyCollection())).thenReturn(page);
    }

    @Test
    void theFourCountsAddUpAndNoSourceCountsAMarkerNotSettledYet() {
        when(editions.countVisibleBySource()).thenReturn(List.of(new SourceCount(null, 5),
            new SourceCount(CreditsMarkerSource.PLEX, 3), new SourceCount(CreditsMarkerSource.OWNER, 2),
            new SourceCount(CreditsMarkerSource.AUTO, 1)));
        when(editions.findCreditsPageIds(anyBoolean(), anyBoolean(), eq(CreditsMarkerSource.PLEX),
            any(Pageable.class))).thenReturn(List.of());

        CreditsList list = service().list(CreditsFilter.PLEX, 0, 25);

        assertThat(list.counts()).isEqualTo(new CreditsAdminDtos.Counts(5, 3, 2, 11));
        assertThat(list.total()).isEqualTo(3);
        assertThat(list.items()).isEmpty();
    }

    @Test
    void theTotalIsTheChosenFiltersCount() {
        when(editions.countVisibleBySource()).thenReturn(List.of(new SourceCount(null, 5),
            new SourceCount(CreditsMarkerSource.PLEX, 3), new SourceCount(CreditsMarkerSource.OWNER, 2)));
        when(editions.findCreditsPageIds(anyBoolean(), anyBoolean(), any(),
            any(Pageable.class))).thenReturn(List.of());

        assertThat(service().list(CreditsFilter.NONE, 0, 25).total()).isEqualTo(5);
        assertThat(service().list(CreditsFilter.OWNER, 0, 25).total()).isEqualTo(2);
        assertThat(service().list(CreditsFilter.ALL, 0, 25).total()).isEqualTo(10);
    }

    @Test
    void aRowCarriesTheFilmTheEditionTheCountTheRuntimeAndPlexsState() {
        Edition edition = seeded(7, "Heat", 1995, 5_000_000L, CreditsMarkerSource.PLEX);
        page(List.of(edition));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(anyCollection())).thenReturn(List.of(new FilmEditionCount(7L, 2)));
        when(markers.findAllById(anyCollection())).thenReturn(List.of(plexRow(7, FINGERPRINT, 5_000_000)));

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
        page(List.of(owner, refused, stale, none));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(anyCollection())).thenReturn(List.of());
        when(markers.findAllById(anyCollection())).thenReturn(List.of(plexRow(1, FINGERPRINT, 5_000_000),
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
        page(List.of(noDuration, noFile));
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.countVisibleByFilmIdIn(anyCollection())).thenReturn(List.of());
        when(markers.findAllById(anyCollection())).thenReturn(List.of(plexRow(1, FINGERPRINT, 5_000_000)));

        List<CreditsRow> items = service().list(CreditsFilter.ALL, 0, 25).items();

        assertThat(items.get(0).runtimeMs()).isNull();
        assertThat(items.get(0).plex().state()).isEqualTo(PlexState.NO_TIMELINE);
        assertThat(items.get(1).runtimeMs()).isNull();
        assertThat(items.get(1).plex().state()).isEqualTo(PlexState.NONE);
        assertThat(items.get(1).editionCount()).isZero();
    }

    @Test
    void theRowsKeepTheOrderOfThePageOfIdsNotTheOrderTheEditionsLoadIn() {
        Edition first = seeded(1, "Alpha", 2000, null, null);
        Edition second = seeded(2, "Beta", 2000, null, null);
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.findCreditsPageIds(anyBoolean(), anyBoolean(), eq(CreditsMarkerSource.PLEX),
            any(Pageable.class))).thenReturn(List.of(1L, 2L));
        when(editions.findWithFilesAndFilmByIdIn(anyCollection())).thenReturn(List.of(second, first));
        when(editions.countVisibleByFilmIdIn(anyCollection())).thenReturn(List.of());
        when(markers.findAllById(anyCollection())).thenReturn(List.of());

        assertThat(service().list(CreditsFilter.ALL, 0, 25).items()).extracting(CreditsRow::title)
            .containsExactly("Alpha", "Beta");
    }

    @Test
    void aSizeAboveTheCapIsCappedAtOneHundredAndEchoedAsSuch() {
        when(editions.countVisibleBySource()).thenReturn(List.of());
        List<Edition> many = LongStream.rangeClosed(1, 100).mapToObj(n -> seeded(n, "Film " + n, 2000, null, null))
            .toList();
        // Only a request for page 2 of exactly 100 rows is answered; any other page size finds nothing.
        when(editions.findCreditsPageIds(anyBoolean(), anyBoolean(), eq(CreditsMarkerSource.PLEX),
            eq(PageRequest.of(2, 100)))).thenReturn(many.stream().map(Edition::getId).toList());
        when(editions.findWithFilesAndFilmByIdIn(anyCollection())).thenReturn(many);
        when(editions.countVisibleByFilmIdIn(anyCollection())).thenReturn(List.of());
        when(markers.findAllById(anyCollection())).thenReturn(List.of());

        CreditsList list = service().list(CreditsFilter.ALL, 2, 5000);

        assertThat(list.size()).isEqualTo(100);
        assertThat(list.page()).isEqualTo(2);
        assertThat(list.items()).hasSize(100);
    }

    @Test
    void aNegativePageAndASizeBelowOneAreClamped() {
        when(editions.countVisibleBySource()).thenReturn(List.of());
        when(editions.findCreditsPageIds(anyBoolean(), anyBoolean(), any(),
            any(Pageable.class))).thenReturn(List.of());

        CreditsList list = service().list(CreditsFilter.ALL, -3, 0);

        assertThat(list.page()).isZero();
        assertThat(list.size()).isEqualTo(1);
    }
}
