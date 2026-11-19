package gregochr.cinefilm.home;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Home in the holiday weeks through the whole application, with the override properties bound. */
@TestPropertySource(properties = {"cinefilm.holiday.christmas.include=901", "cinefilm.holiday.christmas.exclude=902"})
class HomeHolidayApiTest extends ApiTestBase {

    @MockitoSpyBean
    private Clock clock;

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
        reload();
        today("2026-12-14T09:00:00Z");

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.weeklyTheme.kind").value("holiday"))
            .andExpect(jsonPath("$.weeklyTheme.value").value("christmas"))
            .andExpect(jsonPath("$.weeklyTheme.title").value("This week: Christmas"))
            .andExpect(jsonPath("$.weeklyTheme.caption").value("A new theme every Monday"))
            .andExpect(jsonPath("$.weeklyTheme.films[*].title",
                contains("Included", "Tagged C", "Tagged A", "Tagged B", "Tagged D")));
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
