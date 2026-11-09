package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.CollectionFacet;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.playback.PreferencesService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import gregochr.cinefilm.transcode.EncoderMode;
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
    private FilmRepository films;

    @Mock
    private PreferencesService preferences;

    @Mock
    private Transcoder transcoder;

    private FilmService service;

    @BeforeEach
    void setUp() {
        service = new FilmService(loader, films, preferences, transcoder);
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

        FilmList list = service.list(new LibraryQuery("alien", "title", null, null, null, null));

        assertThat(list.total()).isEqualTo(2);
        assertThat(list.films()).extracting(FilmDtos.FilmCard::title).containsExactly("Alien", "Aliens");
        assertThat(list.facets().genres()).containsExactly("Crime", "Drama", "Horror", "sci-fi", "Thriller");
        assertThat(list.facets().decades()).containsExactly(1960, 1970, 1990);
        assertThat(list.facets().collections()).containsExactly(new CollectionFacet(9L, "Alien"));
    }

    @Test
    void collectionFacetsAreDistinctAndSortedByName() {
        FilmCollection zodiacs = withId(2L, "Zodiac Saga");
        FilmCollection alien = withId(1L, "Alien");
        Film a = owned(1L, "Z1", 2000);
        a.setCollection(zodiacs);
        Film b = owned(2L, "Z2", 2001);
        b.setCollection(zodiacs);
        Film c = owned(3L, "A1", 1979);
        c.setCollection(alien);
        Film d = owned(4L, "A2", 1986);
        d.setCollection(alien);
        when(loader.loadAll()).thenReturn(List.of(view(a), view(b), view(c), view(d)));

        FilmList list = service.list(LibraryQuery.all());

        assertThat(list.facets().collections())
            .containsExactly(new CollectionFacet(1L, "Alien"), new CollectionFacet(2L, "Zodiac Saga"));
    }

    @Test
    void aCollectionWithOnlyOneFilmInTheLibraryIsNotAFacet() {
        FilmCollection lone = withId(5L, "Lone Sequel");
        FilmCollection pair = withId(6L, "Pair");
        Film a = owned(1L, "L1", 2000);
        a.setCollection(lone);
        Film b = owned(2L, "P1", 2001);
        b.setCollection(pair);
        Film c = owned(3L, "P2", 2002);
        c.setCollection(pair);
        when(loader.loadAll()).thenReturn(List.of(view(a), view(b), view(c)));

        assertThat(service.list(LibraryQuery.all()).facets().collections())
            .containsExactly(new CollectionFacet(6L, "Pair"));
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
        assertThat(list.facets().collections()).isEmpty();
    }

    @Test
    void aBadQueryValueIsRejected() {
        when(loader.loadAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.list(new LibraryQuery(null, "popularity", null, null, null, null)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("sort must be title, year or added");
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
