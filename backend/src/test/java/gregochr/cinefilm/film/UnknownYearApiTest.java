package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.person;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.person.Person;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A film whose year is unknown has {@code year: null} everywhere and never sorts, filters or themes as year 0. */
class UnknownYearApiTest extends ApiTestBase {

    private Film seventies;
    private Film sixties;
    private Film unknown;
    private Person actor;

    @Autowired
    private gregochr.cinefilm.person.PersonRepository personRepository;

    private Film add(String title, Integer year, FilmCollection collection) {
        Film film = film(null, title, year, NOW.minus(Duration.ofDays(10)));
        film.setCollection(collection);
        edition(film, null, "Original", 100 * MIN, null);
        return film;
    }

    @BeforeEach
    void data() {
        FilmCollection series = collections.save(collection("Series"));
        sixties = add("Alpha", 1965, series);
        seventies = add("Beta", 1975, series);
        unknown = add("Gamma", null, series);
        actor = personRepository.save(person(null, "Someone"));
        credit(sixties, actor, CreditRole.CAST, "A", 0);
        credit(seventies, actor, CreditRole.CAST, "B", 0);
        credit(unknown, actor, CreditRole.CAST, "C", 0);
        save(unknown);
        save(sixties);
        save(seventies);
    }

    @Test
    void theApiReturnsNullForAnUnknownYear() throws Exception {
        mvc.perform(get("/api/films/{id}", unknown.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.year").value(nullValue()))
            .andExpect(jsonPath("$.title").value("Gamma"));
        mvc.perform(get("/api/films/{id}", seventies.getId())).andExpect(jsonPath("$.year").value(1975));
        mvc.perform(get("/api/films?q=Gamma")).andExpect(jsonPath("$.films[0].year").value(nullValue()));
    }

    @Test
    void sortingByYearPutsTheUnknownLastAndTitleSortBreaksTiesWithoutFailing() throws Exception {
        mvc.perform(get("/api/films?sort=year")).andExpect(jsonPath("$.films[*].title",
            contains("Alpha", "Beta", "Gamma")));
        mvc.perform(get("/api/films?sort=title")).andExpect(jsonPath("$.films[*].title",
            contains("Alpha", "Beta", "Gamma")));
    }

    @Test
    void aDecadeFilterAndTheDecadeFacetsIgnoreTheUnknown() throws Exception {
        mvc.perform(get("/api/films?decade=1970")).andExpect(jsonPath("$.films", hasSize(1)))
            .andExpect(jsonPath("$.films[0].title").value("Beta"));
        mvc.perform(get("/api/films?decade=0")).andExpect(jsonPath("$.films", hasSize(0)));
        mvc.perform(get("/api/films")).andExpect(jsonPath("$.facets.decades", contains(1960, 1970)));
    }

    @Test
    void thePersonPageListsNewestFirstWithTheUnknownLast() throws Exception {
        mvc.perform(get("/api/people/{id}", actor.getId()))
            .andExpect(jsonPath("$.films[*].film.title", contains("Beta", "Alpha", "Gamma")))
            .andExpect(jsonPath("$.films[2].film.year").value(nullValue()));
    }

    @Test
    void aCollectionListsItsOwnedFilmsOldestFirstWithTheUnknownLast() throws Exception {
        mvc.perform(get("/api/collections/{id}", unknown.getCollection().getId()))
            .andExpect(jsonPath("$.parts[*].film.title", contains("Alpha", "Beta", "Gamma")));
    }

    @Test
    void homeStillBuildsAWeeklyThemeWhenSomeFilmsHaveNoYear() throws Exception {
        for (int i = 0; i < 5; i++) {
            save(add("Seventies " + i, 1970 + i, null));
        }
        mvc.perform(get("/api/home")).andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklyTheme.kind").value("decade"))
            .andExpect(jsonPath("$.weeklyTheme.value").value("1970"));
    }
}
