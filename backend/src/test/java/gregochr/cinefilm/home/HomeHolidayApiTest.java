package gregochr.cinefilm.home;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.ChristmasChoice;
import gregochr.cinefilm.film.ChristmasOverrides;
import gregochr.cinefilm.film.Film;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Home in the holiday weeks through the whole application, with the owner's overrides in the table. */
class HomeHolidayApiTest extends ApiTestBase {

    @MockitoSpyBean
    private Clock clock;

    @Autowired
    private ChristmasOverrides overrides;

    @Autowired
    private EntityManager entityManager;

    private void today(String instant) {
        doReturn(Instant.parse(instant)).when(clock).instant();
    }

    private void add(String title, int year, Long tmdbId, String genre, String... keywords) {
        Film film = film(null, title, year, NOW, genre);
        film.setTmdbId(tmdbId);
        film.getKeywords().addAll(java.util.List.of(keywords));
        edition(film, null, "Theatrical Cut", 100 * MIN, null);
        save(film);
    }

    /** Writes everything and empties the persistence context, so the request reads the films afresh. */
    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void christmasWeekShowsTaggedAndIncludedFilmsAndNotExcludedOnes() throws Exception {
        add("Tagged A", 1988, 1L, "Action", "christmas", "terrorism");
        add("Tagged B", 1990, 2L, "Comedy", "christmas");
        add("Tagged C", 1984, 3L, "Comedy", "christmas");
        add("Included", 1946, 901L, "Drama");
        add("Tagged D", 2003, 4L, "Comedy", "christmas");
        add("Excluded", 1980, 902L, "Comedy", "christmas");
        add("Plain", 1970, 5L, "Drama", "heist");
        overrides.set(901L, ChristmasChoice.INCLUDE);
        overrides.set(902L, ChristmasChoice.EXCLUDE);
        reload();
        today("2026-12-14T09:00:00Z");

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklyTheme.kind").value("holiday"))
            .andExpect(jsonPath("$.weeklyTheme.value").value("christmas"))
            .andExpect(jsonPath("$.weeklyTheme.title").value("This week: Christmas"))
            .andExpect(jsonPath("$.weeklyTheme.caption").value("A new theme every Monday"))
            .andExpect(jsonPath("$.weeklyTheme.films[0].title").value("Included"))
            .andExpect(jsonPath("$.weeklyTheme.films[*].title",
                containsInAnyOrder("Included", "Tagged C", "Tagged A", "Tagged B", "Tagged D")));
    }

    @Test
    void homeFollowsTheTableOnEveryLoad() throws Exception {
        add("Tagged A", 1988, 1L, "Action", "christmas");
        add("Tagged B", 1990, 2L, "Comedy", "christmas");
        add("Tagged C", 1984, 3L, "Comedy", "christmas");
        add("Tagged D", 2003, 4L, "Comedy", "christmas");
        add("Tagged E", 2005, 6L, "Comedy", "christmas");
        add("Extra", 1950, 901L, "Drama");
        reload();
        today("2026-12-14T09:00:00Z");

        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.weeklyTheme.films[*].title", containsInAnyOrder("Tagged C", "Tagged A",
                "Tagged B", "Tagged D", "Tagged E")));

        overrides.set(901L, ChristmasChoice.INCLUDE);
        overrides.set(3L, ChristmasChoice.EXCLUDE);
        reload();
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.weeklyTheme.films[0].title").value("Extra"))
            .andExpect(jsonPath("$.weeklyTheme.films[*].title", containsInAnyOrder("Extra", "Tagged A",
                "Tagged B", "Tagged D", "Tagged E")));

        overrides.set(901L, ChristmasChoice.EXCLUDE);
        overrides.remove(3L);
        reload();
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.weeklyTheme.films[*].title", containsInAnyOrder("Tagged C", "Tagged A",
                "Tagged B", "Tagged D", "Tagged E")));
    }

    @Test
    void halloweenWeekShowsHorrorFilms() throws Exception {
        for (int i = 0; i < 6; i++) {
            add("Scary " + i, 1970 + i, null, "Horror");
        }
        add("Gentle", 1960, null, "Comedy");
        reload();
        today("2026-10-25T20:00:00Z");

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklyTheme.kind").value("holiday"))
            .andExpect(jsonPath("$.weeklyTheme.value").value("halloween"))
            .andExpect(jsonPath("$.weeklyTheme.title").value("This week: Halloween"))
            .andExpect(jsonPath("$.weeklyTheme.films", hasSize(6)));
    }

    @Test
    void anOrdinaryWeekIsStillTheRotation() throws Exception {
        for (int i = 0; i < 6; i++) {
            add("Scary " + i, 1970 + i, null, "Horror", "christmas");
        }
        reload();
        today("2026-11-18T09:00:00Z");

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklyTheme.kind").value(org.hamcrest.Matchers.not("holiday")));
    }
}
