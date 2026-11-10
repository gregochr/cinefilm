package gregochr.cinefilm.collection;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.CollectionDtos.CollectionCard;
import gregochr.cinefilm.collection.CollectionDtos.CollectionList;
import gregochr.cinefilm.collection.CollectionDtos.CollectionPage;
import gregochr.cinefilm.collection.CollectionDtos.PartDto;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionServiceTest {

    private static final long ALIEN_ID = 9L;

    @Mock
    private FilmCollectionRepository collections;

    @Mock
    private FilmRepository films;

    @Mock
    private LibraryLoader loader;

    private CollectionService service;
    private FilmCollection alienSaga;

    @BeforeEach
    void setUp() {
        service = new CollectionService(collections, films, loader);
        alienSaga = collection("Alien");
        alienSaga.setId(ALIEN_ID);
        alienSaga.setPosterPath("/alien-saga.jpg");
    }

    private void part(FilmCollection owner, long tmdbId, String title, int year, int order, String poster) {
        CollectionPart part = new CollectionPart();
        part.setCollection(owner);
        part.setTmdbId(tmdbId);
        part.setTitle(title);
        part.setYear(year);
        part.setReleaseOrder(order);
        part.setPosterPath(poster);
        owner.getParts().add(part);
    }

    private Film owned(long id, String title, int year, Long tmdbId, FilmCollection owner, String poster) {
        Film film = film(id, title, year, NOW);
        film.setTmdbId(tmdbId);
        film.setCollection(owner);
        film.setPosterPath(poster);
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return film;
    }

    // get

    @Test
    void anUnknownCollectionIsNotFound() {
        assertThatThrownBy(() -> service.get(404L))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such collection");
    }

    @Test
    void thePageListsPartsInReleaseOrderWithMissingOnesInPlaceAndStrayOwnedFilmsLast() {
        part(alienSaga, 348L, "Alien", 1979, 1, "/a1.jpg");
        part(alienSaga, 679L, "Aliens", 1986, 2, "/a2.jpg");
        part(alienSaga, 8077L, "Alien 3", 1992, 3, "/a3.jpg");
        Film alien = owned(1L, "Alien", 1979, 348L, alienSaga, "/own-a1.jpg");
        Film alien3 = owned(3L, "Alien 3", 1992, 8077L, alienSaga, "/own-a3.jpg");
        Film noTmdbZ = owned(4L, "Zeta Cut", 1990, null, alienSaga, null);
        Film noTmdbA = owned(5L, "Alpha Cut", 1990, null, alienSaga, null);
        Film earlier = owned(6L, "Prequel Cut", 1985, 1L, alienSaga, null);
        when(collections.findById(ALIEN_ID)).thenReturn(Optional.of(alienSaga));
        when(films.findByCollectionId(ALIEN_ID)).thenReturn(List.of(alien3, noTmdbZ, alien, noTmdbA, earlier));
        when(loader.views(List.of(alien3, noTmdbZ, alien, noTmdbA, earlier)))
            .thenReturn(List.of(view(alien3), view(noTmdbZ), view(alien), view(noTmdbA), view(earlier)));

        CollectionPage page = service.get(ALIEN_ID);

        assertThat(page.id()).isEqualTo(ALIEN_ID);
        assertThat(page.name()).isEqualTo("Alien");
        assertThat(page.posterPath()).isEqualTo("/alien-saga.jpg");
        assertThat(page.parts()).hasSize(6);
        assertThat(page.parts().get(0).owned()).isTrue();
        assertThat(page.parts().get(0).film().title()).isEqualTo("Alien");
        assertThat(page.parts().get(1)).isEqualTo(new PartDto(false, null, "Aliens", 1986, "/a2.jpg"));
        assertThat(page.parts().get(2).film().title()).isEqualTo("Alien 3");
        assertThat(page.parts().subList(3, 6)).allMatch(PartDto::owned);
        assertThat(page.parts().subList(3, 6)).extracting(p -> p.film().title())
            .containsExactly("Prequel Cut", "Alpha Cut", "Zeta Cut");
    }

    // cards

    @Test
    void cardsCountOwnedAndTotalAndFanOutAtMostThreeOwnedPosters() {
        part(alienSaga, 1L, "Alien", 1979, 1, null);
        part(alienSaga, 2L, "Aliens", 1986, 2, null);
        part(alienSaga, 3L, "Alien 3", 1992, 3, null);
        part(alienSaga, 4L, "Alien Resurrection", 1997, 4, null);
        Film a1 = owned(1L, "Alien", 1979, 1L, alienSaga, "/p1.jpg");
        Film a3 = owned(3L, "Alien 3", 1992, 3L, alienSaga, "/p3.jpg");
        Film a4 = owned(4L, "Alien Resurrection", 1997, 4L, alienSaga, "/p4.jpg");
        Film extra = owned(5L, "Prometheus", 2012, 99L, alienSaga, "/p5.jpg");
        when(collections.findAll()).thenReturn(List.of(alienSaga));

        List<CollectionCard> cards = service.cards(List.of(view(a1), view(a3), view(a4), view(extra)));

        assertThat(cards).hasSize(1);
        CollectionCard card = cards.get(0);
        assertThat(card.id()).isEqualTo(ALIEN_ID);
        assertThat(card.name()).isEqualTo("Alien");
        assertThat(card.posterPath()).isEqualTo("/alien-saga.jpg");
        assertThat(card.ownedCount()).isEqualTo(4);
        assertThat(card.totalCount()).isEqualTo(5);
        assertThat(card.posterPaths()).containsExactly("/p1.jpg", "/p3.jpg", "/p4.jpg");
    }

    @Test
    void aCollectionWithNoOwnedFilmsHasNoCardAndFilmsOfOtherCollectionsDoNotCount() {
        FilmCollection other = collection("Other");
        other.setId(10L);
        Film loner = owned(7L, "Loner", 2000, 7L, null, null);
        Film elsewhere = owned(8L, "Elsewhere", 2001, 8L, other, null);
        when(collections.findAll()).thenReturn(List.of(alienSaga));

        List<CollectionCard> cards = service.cards(List.of(view(loner), view(elsewhere)));

        assertThat(cards).isEmpty();
    }

    @Test
    void aCollectionWithOneOwnedFilmHasNoCardButTwoMakeOne() {
        part(alienSaga, 1L, "Alien", 1979, 1, null);
        part(alienSaga, 2L, "Aliens", 1986, 2, null);
        Film a1 = owned(1L, "Alien", 1979, 1L, alienSaga, null);
        Film a2 = owned(2L, "Aliens", 1986, 2L, alienSaga, null);
        when(collections.findAll()).thenReturn(List.of(alienSaga));

        assertThat(service.cards(List.of(view(a1)))).isEmpty();
        assertThat(service.cards(List.of(view(a1), view(a2)))).extracting(CollectionCard::ownedCount)
            .containsExactly(2);
    }

    @Test
    void cardsFanPostersEarliestReleaseFirstAndCarryTheNewestAddedAt() {
        part(alienSaga, 1L, "Alien", 1979, 1, null);
        part(alienSaga, 2L, "Aliens", 1986, 2, null);
        Film sequel = owned(2L, "Aliens", 1986, 2L, alienSaga, "/p2.jpg");
        Film first = owned(1L, "Alien", 1979, 1L, alienSaga, "/p1.jpg");
        sequel.setAddedAt(NOW.minus(Duration.ofDays(1)));
        first.setAddedAt(NOW.minus(Duration.ofDays(9)));
        when(collections.findAll()).thenReturn(List.of(alienSaga));

        CollectionCard card = service.cards(List.of(view(sequel), view(first))).get(0);

        assertThat(card.posterPaths()).containsExactly("/p1.jpg", "/p2.jpg");
        assertThat(card.lastAddedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void listCountsEveryFilmInTheLibraryAndTheQualifyingCollections() {
        part(alienSaga, 1L, "Alien", 1979, 1, null);
        Film a1 = owned(1L, "Alien", 1979, 1L, alienSaga, null);
        Film a2 = owned(2L, "Aliens", 1986, 2L, alienSaga, null);
        Film other = owned(3L, "Heat", 1995, 3L, null, null);
        List<FilmView> library = List.of(view(a1), view(a2), view(other));
        when(loader.loadAll()).thenReturn(library);
        when(collections.findAll()).thenReturn(List.of(alienSaga));

        CollectionList list = service.list();

        assertThat(list.filmCount()).isEqualTo(3);
        assertThat(list.collections()).extracting(CollectionCard::name).containsExactly("Alien");
    }

    @Test
    void cardsAreSortedByName() {
        FilmCollection zebra = collection("Zebra");
        zebra.setId(1L);
        FilmCollection apple = collection("Apple");
        apple.setId(2L);
        Film z = owned(1L, "Z", 2000, 1L, zebra, null);
        Film z2 = owned(3L, "Z2", 2000, 3L, zebra, null);
        Film a = owned(2L, "A", 2001, 2L, apple, null);
        Film a2 = owned(4L, "A2", 2001, 4L, apple, null);
        when(collections.findAll()).thenReturn(List.of(zebra, apple));
        List<FilmView> library = List.of(view(z), view(z2), view(a), view(a2));

        assertThat(service.cards(library)).extracting(CollectionCard::name).containsExactly("Apple", "Zebra");
    }
}
