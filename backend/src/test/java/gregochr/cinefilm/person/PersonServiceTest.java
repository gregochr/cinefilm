package gregochr.cinefilm.person;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.person;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRepository;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.person.PersonDtos.PersonPage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonServiceTest {

    private static final long PERSON_ID = 3L;

    @Mock
    private PersonRepository people;

    @Mock
    private CreditRepository credits;

    @Mock
    private LibraryLoader loader;

    private PersonService service;
    private Person welles;

    @BeforeEach
    void setUp() {
        service = new PersonService(people, credits, loader);
        welles = person(PERSON_ID, "Orson Welles");
        welles.setProfilePath("/welles.jpg");
    }

    private Film ownedFilm(long id, String title, int year) {
        Film film = film(id, title, year, NOW);
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return film;
    }

    /** Lets the loader wrap whatever films it is given, as the real one does. */
    private void loaderWrapsFilms() {
        when(loader.views(anyCollection())).thenAnswer(call -> {
            Collection<Film> films = call.getArgument(0);
            return films.stream().map(f -> new FilmView(f, java.util.Map.of())).toList();
        });
    }

    @Test
    void anUnknownPersonIsNotFound() {
        assertThatThrownBy(() -> service.get(404L))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("No such person");
    }

    @Test
    void aPersonWithNoCreditsHasAnEmptyFilmList() {
        when(people.findById(PERSON_ID)).thenReturn(Optional.of(welles));
        when(credits.findByPersonId(PERSON_ID)).thenReturn(List.of());
        loaderWrapsFilms();

        PersonPage page = service.get(PERSON_ID);

        assertThat(page.id()).isEqualTo(PERSON_ID);
        assertThat(page.name()).isEqualTo("Orson Welles");
        assertThat(page.profilePath()).isEqualTo("/welles.jpg");
        assertThat(page.films()).isEmpty();
    }

    @Test
    void creditsOnTheSameFilmAreGroupedInCreditOrderAndDescribedByRole() {
        Film kane = ownedFilm(1L, "Citizen Kane", 1941);
        Credit directed = credit(kane, welles, CreditRole.DIRECTOR, null, 0);
        Credit acted = credit(kane, welles, CreditRole.CAST, "Charles Foster Kane", 1);
        Credit unnamed = credit(kane, welles, CreditRole.CAST, null, 2);
        Credit blank = credit(kane, welles, CreditRole.CAST, "   ", 3);
        when(people.findById(PERSON_ID)).thenReturn(Optional.of(welles));
        when(credits.findByPersonId(PERSON_ID)).thenReturn(List.of(directed, acted, unnamed, blank));
        loaderWrapsFilms();

        PersonPage page = service.get(PERSON_ID);

        assertThat(page.films()).hasSize(1);
        assertThat(page.films().get(0).film().title()).isEqualTo("Citizen Kane");
        assertThat(page.films().get(0).roles())
            .containsExactly("Director", "as Charles Foster Kane", "Actor", "Actor");
    }

    @Test
    void filmsAreNewestFirstThenBySortTitle() {
        Film kane = ownedFilm(1L, "Citizen Kane", 1941);
        Film theThirdMan = ownedFilm(2L, "The Third Man", 1949);
        Film touch = ownedFilm(3L, "Touch of Evil", 1958);
        Film macbeth = ownedFilm(4L, "Macbeth", 1948);
        Film alsoFortyNine = ownedFilm(5L, "Black Magic", 1949);
        List<Credit> all = List.of(
            credit(kane, welles, CreditRole.CAST, "Kane", 0),
            credit(theThirdMan, welles, CreditRole.CAST, "Harry Lime", 0),
            credit(touch, welles, CreditRole.CAST, "Quinlan", 0),
            credit(macbeth, welles, CreditRole.DIRECTOR, null, 0),
            credit(alsoFortyNine, welles, CreditRole.CAST, "Cagliostro", 0));
        when(people.findById(PERSON_ID)).thenReturn(Optional.of(welles));
        when(credits.findByPersonId(PERSON_ID)).thenReturn(all);
        loaderWrapsFilms();

        PersonPage page = service.get(PERSON_ID);

        assertThat(page.films()).extracting(f -> f.film().title())
            .containsExactly("Touch of Evil", "Black Magic", "The Third Man", "Macbeth", "Citizen Kane");
    }
}
