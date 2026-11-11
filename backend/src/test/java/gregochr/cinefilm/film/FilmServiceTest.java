package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.playback.PreferencesService;
import gregochr.cinefilm.settings.ServerSettingsService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import gregochr.cinefilm.transcode.EncoderMode;
import gregochr.cinefilm.transcode.CutList;
import gregochr.cinefilm.transcode.KeyframeIndexService;
import gregochr.cinefilm.transcode.Transcoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FilmServiceTest {

    @Mock
    private LibraryLoader loader;

    @Mock
    private ServerSettingsService settings;

    @Mock
    private FilmRepository films;

    @Mock
    private PreferencesService preferences;

    @Mock
    private Transcoder transcoder;

    @Mock
    private KeyframeIndexService indexes;

    private FilmService service;

    @BeforeEach
    void setUp() {
        service = new FilmService(loader, films, preferences, transcoder, indexes, settings);
    }

    private static Film owned(long id, String title, int year, String... genres) {
        Film film = film(id, title, year, NOW, genres);
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return film;
    }

    private static FilmCollection withId(long id, String name) {
        FilmCollection collection = collection(name);
        collection.setId(id);
        return collection;
    }

    @Test
    void listFiltersAndSortsButTheFacetsCoverTheWholeLibrary() {
        FilmCollection alienSaga = withId(9L, "Alien");
        Film heat = owned(1L, "Heat", 1995, "Crime", "Thriller");
        Film alien = owned(2L, "Alien", 1979, "sci-fi", "Horror");
        alien.setCollection(alienSaga);
        Film aliens = owned(4L, "Aliens", 1979, "sci-fi");
        aliens.setCollection(alienSaga);
        Film kes = owned(3L, "Kes", 1969, "Drama");
        when(loader.loadAll()).thenReturn(List.of(view(heat), view(alien), view(aliens), view(kes)));

        FilmList list = service.list(new LibraryQuery("alien", "title", null, null, null));

        assertThat(list.total()).isEqualTo(2);
        assertThat(list.films()).extracting(FilmDtos.LibraryCard::title).containsExactly("Alien", "Aliens");
        assertThat(list.facets().genres()).containsExactly("Crime", "Drama", "Horror", "sci-fi", "Thriller");
        assertThat(list.facets().decades()).containsExactly(1960, 1970, 1990);
    }

    @Test
    void detailHasNoCollectionWhenOnlyOneFilmOfItIsBrowsable() {
        FilmCollection saga = withId(7L, "Saga");
        Film one = owned(1L, "One", 2000);
        one.setCollection(saga);
        Film hidden = owned(2L, "Two", 2002);
        hidden.setCollection(saga);
        hidden.getEditions().get(0).getMediaFiles().clear();
        file(hidden.getEditions().get(0), "two.m4v").setPresence(Presence.MISSING);
        when(films.findById(1L)).thenReturn(Optional.of(one));
        when(films.findByCollectionId(7L)).thenReturn(List.of(one, hidden));
        when(loader.views(List.of(one))).thenReturn(List.of(view(one)));

        assertThat(service.detail(1L).collection()).isNull();
    }

    @Test
    void cardsCarryTheCollectionIdOnlyWhenTheCollectionHasTwoFilmsInTheLibrary() {
        FilmCollection pair = withId(6L, "Pair");
        FilmCollection lone = withId(5L, "Lone Sequel");
        Film p1 = owned(1L, "P1", 2001);
        p1.setCollection(pair);
        Film p2 = owned(2L, "P2", 2002);
        p2.setCollection(pair);
        Film l1 = owned(3L, "L1", 2000);
        l1.setCollection(lone);
        Film heat = owned(4L, "Heat", 1995);
        when(loader.loadAll()).thenReturn(List.of(view(p1), view(p2), view(l1), view(heat)));

        FilmList list = service.list(LibraryQuery.all());

        assertThat(list.films()).extracting(FilmDtos.LibraryCard::title, FilmDtos.LibraryCard::collectionId)
            .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("P1", 6L),
                org.assertj.core.groups.Tuple.tuple("P2", 6L), org.assertj.core.groups.Tuple.tuple("L1", null),
                org.assertj.core.groups.Tuple.tuple("Heat", null));
    }

    @Test
    void aFilterThatLeavesOneFilmOfACollectionStillCarriesItsCollectionId() {
        FilmCollection pair = withId(6L, "Pair");
        Film p1 = owned(1L, "P1", 2001);
        p1.setCollection(pair);
        Film p2 = owned(2L, "P2", 2002);
        p2.setCollection(pair);
        when(loader.loadAll()).thenReturn(List.of(view(p1), view(p2)));

        FilmList list = service.list(new LibraryQuery("p1", null, null, null, null));

        assertThat(list.films()).extracting(FilmDtos.LibraryCard::collectionId).containsExactly(6L);
    }

    @Test
    void detailHasTheCollectionWhenTwoFilmsOfItAreBrowsable() {
        FilmCollection saga = withId(7L, "Saga");
        Film one = owned(1L, "One", 2000);
        one.setCollection(saga);
        Film two = owned(2L, "Two", 2002);
        two.setCollection(saga);
        when(films.findById(1L)).thenReturn(Optional.of(one));
        when(films.findByCollectionId(7L)).thenReturn(List.of(one, two));
        when(loader.views(List.of(one))).thenReturn(List.of(view(one)));

        assertThat(service.detail(1L).collection()).isEqualTo(new FilmDtos.CollectionRef(7L, "Saga"));
    }

    @Test
    void anEmptyLibraryListsNothingAndHasNoFacets() {
        when(loader.loadAll()).thenReturn(List.of());

        FilmList list = service.list(LibraryQuery.all());

        assertThat(list.total()).isZero();
        assertThat(list.films()).isEmpty();
        assertThat(list.facets().genres()).isEmpty();
        assertThat(list.facets().decades()).isEmpty();
    }

    @Test
    void listAppliesTheServersMinimumWatchTimeToTheFilterAndTheCards() {
        when(settings.minWatchMs()).thenReturn(5 * MIN);
        Film barely = owned(1L, "Barely", 2000);
        Film half = owned(2L, "Half", 2001);
        when(loader.loadAll()).thenReturn(List.of(
            view(barely, state(barely.getEditions().get(0), 4 * MIN, false, NOW, null)),
            view(half, state(half.getEditions().get(0), 50 * MIN, false, NOW, null))));

        FilmList inProgress = service.list(new LibraryQuery(null, "title", null, null, "in-progress"));
        FilmList unwatched = service.list(new LibraryQuery(null, "title", null, null, "unwatched"));

        assertThat(inProgress.films()).extracting(FilmDtos.LibraryCard::title).containsExactly("Half");
        assertThat(inProgress.films().get(0).inProgress()).isTrue();
        assertThat(unwatched.films()).extracting(FilmDtos.LibraryCard::title).containsExactly("Barely");
        assertThat(unwatched.films().get(0).inProgress()).isFalse();
        assertThat(unwatched.films().get(0).progress()).isNotNull();
    }

    @Test
    void aBadQueryValueIsRejected() {
        when(loader.loadAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.list(new LibraryQuery(null, "popularity", null, null, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("sort must be title, year, added, imdb, rt or last-watched");
    }

    @Test
    void detailResolvesThePreferencesOfTheFilm() {
        Film heat = owned(1L, "Heat", 1995, "Crime");
        Preferences resolved = new Preferences(10L, "720p", null, "off");
        when(films.findById(1L)).thenReturn(Optional.of(heat));
        when(loader.views(List.of(heat))).thenReturn(List.of(view(heat)));
        when(preferences.resolve(heat)).thenReturn(resolved);
        when(transcoder.mode()).thenReturn(EncoderMode.UNAVAILABLE);

        FilmDetail detail = service.detail(1L);

        assertThat(detail.id()).isEqualTo(1L);
        assertThat(detail.title()).isEqualTo("Heat");
        assertThat(detail.preferences()).isEqualTo(resolved);
        assertThat(detail.editions()).hasSize(1);
        assertThat(detail.editions().get(0).qualities()).extracting(FilmDtos.QualityDto::available)
            .containsOnly(false, true);
    }

    @Test
    void detailListsTheTopTranscodedRungWhenTheStoredCutListForbidsCopying() {
        Film heat = owned(1L, "Heat", 1995, "Crime");
        MediaFile original = file(heat.getEditions().get(0), "heat.mp4");
        original.setVideoCodec("H.264");
        original.setPixelFormat("yuv420p");
        when(films.findById(1L)).thenReturn(Optional.of(heat));
        when(loader.views(List.of(heat))).thenReturn(List.of(view(heat)));
        when(preferences.resolve(heat)).thenReturn(new Preferences(10L, "auto", null, "off"));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.cachedFor(original, false)).thenReturn(Optional.of(new KeyframeIndexService.Index(
            CutList.fromKeyframes(List.of(), 100 * MIN), null)));

        assertThat(service.detail(1L).editions().get(0).qualities()).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original", "1080p", "720p", "480p", "360p");
    }

    @Test
    void detailReadsThePartsOfASplitEditionsIndexesAtTheirVideoDuration() {
        Film heat = owned(1L, "Heat", 1995, "Crime");
        Edition edition = heat.getEditions().get(0);
        MediaFile first = file(edition, "pt1.mp4");
        MediaFile second = file(edition, "pt2.mp4");
        second.setPartNumber(2);
        first.setId(1L);
        second.setId(2L);
        for (MediaFile part : List.of(first, second)) {
            part.setVideoCodec("H.264");
            part.setPixelFormat("yuv420p");
        }
        when(films.findById(1L)).thenReturn(Optional.of(heat));
        when(loader.views(List.of(heat))).thenReturn(List.of(view(heat)));
        when(preferences.resolve(heat)).thenReturn(new Preferences(10L, "auto", null, "off"));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        CutList copyable = CutList.fromKeyframes(java.util.stream.IntStream.range(0, 15).mapToObj(i -> i * 4000L)
            .toList(), 60_000);
        when(indexes.cachedFor(first, true)).thenReturn(Optional.of(new KeyframeIndexService.Index(copyable, null)));
        when(indexes.cachedFor(second, true)).thenReturn(Optional.of(new KeyframeIndexService.Index(copyable, null)));

        assertThat(service.detail(1L).editions().get(0).qualities()).filteredOn(q -> q.id().equals("original"))
            .extracting(FilmDtos.QualityDto::available).containsExactly(true);
    }

    @Test
    void detailUsesTheCodecAloneWhenTheFilmIsNotIndexedYet() {
        Film heat = owned(1L, "Heat", 1995, "Crime");
        MediaFile original = file(heat.getEditions().get(0), "heat.mp4");
        original.setVideoCodec("H.264");
        original.setPixelFormat("yuv420p");
        when(films.findById(1L)).thenReturn(Optional.of(heat));
        when(loader.views(List.of(heat))).thenReturn(List.of(view(heat)));
        when(preferences.resolve(heat)).thenReturn(new Preferences(10L, "auto", null, "off"));
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexes.cachedFor(original, false)).thenReturn(Optional.empty());

        assertThat(service.detail(1L).editions().get(0).qualities()).extracting(FilmDtos.QualityDto::id)
            .containsExactly("auto", "original", "720p", "480p", "360p");
    }

    @Test
    void detailOfAnUnknownFilmIsNotFound() {
        assertThatThrownBy(() -> service.detail(404L))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such film");
    }

    @Test
    void aFilmWhoseFilesAreAllMissingHasNoDetailPage() {
        Film gone = owned(9L, "Gone", 2000);
        gone.getEditions().get(0).getMediaFiles().clear();
        file(gone.getEditions().get(0), "gone.m4v").setPresence(Presence.MISSING);
        when(films.findById(9L)).thenReturn(Optional.of(gone));

        assertThatThrownBy(() -> service.detail(9L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such film");
    }
}
