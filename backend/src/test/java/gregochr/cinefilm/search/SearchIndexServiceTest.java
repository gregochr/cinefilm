package gregochr.cinefilm.search;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.person;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.collection.CollectionService;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.search.SearchIndexDtos.CollectionEntry;
import gregochr.cinefilm.search.SearchIndexDtos.FilmEntry;
import gregochr.cinefilm.search.SearchIndexDtos.PersonEntry;
import gregochr.cinefilm.search.SearchIndexDtos.SearchIndex;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

    private static final long RUNTIME_MIN = 123;

    @Mock
    private LibraryLoader loader;

    @Mock
    private FilmCollectionRepository collectionRepository;

    @Mock
    private FilmRepository filmRepository;

    private SearchIndexService service;

    @BeforeEach
    void setUp() {
        // The real collection service over mocked repositories: the index must reuse its cards, not re-derive them.
        service = new SearchIndexService(loader,
            new CollectionService(collectionRepository, filmRepository, loader,
                org.mockito.Mockito.mock(gregochr.cinefilm.settings.ServerSettingsService.class)));
    }

    private Film saved(long id, String title, int year, String poster) {
        Film film = film(id, title, year, NOW);
        film.setPosterPath(poster);
        edition(film, id * 10, "Theatrical Cut", RUNTIME_MIN * MIN, null);
        return film;
    }

    private SearchIndex build(FilmView... views) {
        when(loader.loadAll()).thenReturn(List.of(views));
        return service.build();
    }

    // films

    @Test
    void aFilmCarriesItsTitleYearPosterSortTitleAndDirectorsInCreditOrder() {
        Film warrior = saved(1, "Warrior", 2011, "/warrior.jpg");
        credit(warrior, person(40L, "Second Director"), CreditRole.DIRECTOR, null, 2);
        credit(warrior, person(41L, "Gavin O'Connor"), CreditRole.DIRECTOR, null, 1);
        credit(warrior, person(42L, "Tom Hardy"), CreditRole.CAST, "Tommy", 1);

        FilmEntry entry = build(view(warrior)).films().get(0);

        assertThat(entry).isEqualTo(new FilmEntry(1L, "Warrior", 2011, List.of("Gavin O'Connor", "Second Director"),
            "/warrior.jpg", null, null, "warrior"));
    }

    @Test
    void aPartWatchedFilmCarriesTheTimeLeftAndWhenItWasLastPlayed() {
        Film warrior = saved(1, "Warrior", 2011, "/warrior.jpg");
        Edition edition = warrior.getEditions().get(0);
        var playedAt = NOW.minus(Duration.ofDays(3));

        FilmEntry entry = build(view(warrior, state(edition, 2 * MIN, false, playedAt, null))).films().get(0);

        assertThat(entry.left()).isEqualTo((RUNTIME_MIN - 2) * MIN);
        assertThat(entry.at()).isEqualTo(playedAt.toString());
    }

    @Test
    void aFilmPlayedButWithNoSavedPositionHasAtButNoLeft() {
        Film warrior = saved(1, "Warrior", 2011, null);
        Edition edition = warrior.getEditions().get(0);

        FilmEntry entry = build(view(warrior, state(edition, 0, true, NOW, NOW))).films().get(0);

        assertThat(entry.left()).isNull();
        assertThat(entry.at()).isEqualTo(NOW.toString());
        assertThat(entry.p()).isNull();
    }

    @Test
    void aFilmNeverPlayedHasNeitherLeftNorAt() {
        FilmEntry entry = build(view(saved(1, "Warrior", 2011, "/w.jpg"))).films().get(0);

        assertThat(entry.left()).isNull();
        assertThat(entry.at()).isNull();
    }

    @Test
    void timeLeftNeverGoesBelowZeroWhenThePositionIsPastTheRuntime() {
        Film warrior = saved(1, "Warrior", 2011, "/w.jpg");
        Edition edition = warrior.getEditions().get(0);

        FilmEntry entry = build(view(warrior, state(edition, (RUNTIME_MIN + 5) * MIN, false, NOW, null)))
            .films().get(0);

        assertThat(entry.left()).isZero();
    }

    @Test
    void filmsCollectionsAndPeopleComeInIdOrderWhateverOrderTheyWereLoaded() {
        Film third = saved(30, "C", 2000, null);
        Film first = saved(10, "A", 2000, null);
        Film second = saved(20, "B", 2000, null);
        credit(third, person(9L, "Nine"), CreditRole.CAST, null, 1);
        credit(first, person(5L, "Five"), CreditRole.CAST, null, 1);
        credit(second, person(7L, "Seven"), CreditRole.DIRECTOR, null, 1);

        SearchIndex index = build(view(third), view(first), view(second));

        assertThat(index.films()).extracting(FilmEntry::id).containsExactly(10L, 20L, 30L);
        assertThat(index.people()).extracting(PersonEntry::id).containsExactly(5L, 7L, 9L);
    }

    // people

    @Test
    void anActorWhoAlsoDirectsIsOnePersonWithACountInEachRole() {
        Person eastwood = person(1L, "Clint Eastwood");
        Film unforgiven = saved(1, "Unforgiven", 1992, null);
        Film gran = saved(2, "Gran Torino", 2008, null);
        Film dirty = saved(3, "Dirty Harry", 1971, null);
        credit(unforgiven, eastwood, CreditRole.DIRECTOR, null, 1);
        credit(unforgiven, eastwood, CreditRole.CAST, "Munny", 1);
        credit(gran, eastwood, CreditRole.DIRECTOR, null, 1);
        credit(gran, eastwood, CreditRole.CAST, "Walt", 1);
        credit(dirty, eastwood, CreditRole.CAST, "Harry", 1);

        List<PersonEntry> people = build(view(unforgiven), view(gran), view(dirty)).people();

        assertThat(people).containsExactly(new PersonEntry(1L, "Clint Eastwood", 3, 2));
    }

    @Test
    void aPersonCreditedTwiceInOneRoleOnOneFilmCountsThatFilmOnce() {
        Person hanks = person(1L, "Tom Hanks");
        Film toy = saved(1, "Toy Story", 1995, null);
        credit(toy, hanks, CreditRole.CAST, "Woody", 1);
        credit(toy, hanks, CreditRole.CAST, "Woody (voice)", 2);

        assertThat(build(view(toy)).people()).containsExactly(new PersonEntry(1L, "Tom Hanks", 1, 0));
    }

    @Test
    void aDirectorOnlyAndAnActorOnlyEachGetTheirOwnRoleCount() {
        Film warrior = saved(1, "Warrior", 2011, null);
        credit(warrior, person(1L, "Gavin O'Connor"), CreditRole.DIRECTOR, null, 1);
        credit(warrior, person(2L, "Tom Hardy"), CreditRole.CAST, "Tommy", 1);

        assertThat(build(view(warrior)).people()).containsExactly(
            new PersonEntry(1L, "Gavin O'Connor", 0, 1),
            new PersonEntry(2L, "Tom Hardy", 1, 0));
    }

    @Test
    void peopleComeOnlyFromTheFilmsTheLoaderReturned() {
        Film shown = saved(1, "Shown", 2000, null);
        Film hidden = saved(2, "Hidden", 2000, null);
        credit(shown, person(1L, "Seen Actor"), CreditRole.CAST, null, 1);
        credit(hidden, person(2L, "Unseen Actor"), CreditRole.CAST, null, 1);

        SearchIndex index = build(view(shown));

        assertThat(index.films()).extracting(FilmEntry::t).containsExactly("Shown");
        assertThat(index.people()).extracting(PersonEntry::n).containsExactly("Seen Actor");
    }

    // collections

    private FilmCollection saga(long id, String name) {
        FilmCollection saga = collection(name);
        saga.setId(id);
        return saga;
    }

    private Film inSaga(long id, FilmCollection saga, String poster) {
        Film film = saved(id, "Part " + id, 2000 + (int) id, poster);
        film.setCollection(saga);
        return film;
    }

    @Test
    void aCollectionNeedsTwoOwnedFilmsAndCarriesOwnedTotalAndTheFirstTwoPosters() {
        FilmCollection lonely = saga(1, "Lonely Collection");
        FilmCollection trilogy = saga(2, "Star Wars Collection");
        when(collectionRepository.findAll()).thenReturn(List.of(trilogy, lonely));
        FilmView only = view(inSaga(1, lonely, "/only.jpg"));
        FilmView a = view(inSaga(2, trilogy, "/a.jpg"));
        FilmView b = view(inSaga(3, trilogy, "/b.jpg"));
        FilmView c = view(inSaga(4, trilogy, "/c.jpg"));

        List<CollectionEntry> collections = build(only, a, b, c).collections();

        assertThat(collections).containsExactly(
            new CollectionEntry(2L, "Star Wars Collection", 3, 3, List.of("/a.jpg", "/b.jpg")));
    }

    @Test
    void theMinimumOwnedBoundaryIsExactlyTwo() {
        FilmCollection pair = saga(1, "Pair Collection");
        when(collectionRepository.findAll()).thenReturn(List.of(pair));

        assertThat(build(view(inSaga(1, pair, "/a.jpg"))).collections()).isEmpty();
        assertThat(build(view(inSaga(1, pair, "/a.jpg")), view(inSaga(2, pair, "/b.jpg"))).collections())
            .extracting(CollectionEntry::o).containsExactly(2);
    }

    @Test
    void collectionsAreInIdOrderAndPostersWithoutAPathAreLeftOut() {
        FilmCollection nine = saga(9, "A Collection");
        FilmCollection three = saga(3, "Z Collection");
        when(collectionRepository.findAll()).thenReturn(List.of(nine, three));

        List<CollectionEntry> collections = build(
            view(inSaga(1, nine, null)), view(inSaga(2, nine, "/b.jpg")),
            view(inSaga(3, three, "/c.jpg")), view(inSaga(4, three, "/d.jpg"))).collections();

        assertThat(collections).extracting(CollectionEntry::id).containsExactly(3L, 9L);
        assertThat(collections.get(1).p()).containsExactly("/b.jpg");
    }
}
