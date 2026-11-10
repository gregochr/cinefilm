package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Ratings end to end: stored on the film, in the detail JSON, and as library sort keys. */
class FilmRatingsApiTest extends ApiTestBase {

    @Autowired
    private EntityManager entityManager;

    /** The test shares a transaction with the request: without this it would read back the instances saved here. */
    private void readFromTheDatabase() {
        films.flush();
        entityManager.clear();
    }

    private Film saved(String title, String imdb, Integer rt) {
        Film film = film(null, title, 2000, NOW);
        film.setImdbId("tt" + Math.abs(title.hashCode()));
        film.setImdbRating(imdb == null ? null : new BigDecimal(imdb));
        film.setImdbVotes(imdb == null ? null : 1234567);
        film.setRtScore(rt);
        film.setRtUrl(rt == null ? null : "https://www.rottentomatoes.com/m/" + title.toLowerCase());
        file(edition(film, null, "Theatrical Cut", 100 * MIN, null), title + ".mp4");
        return save(film);
    }

    @Test
    void theDetailCarriesTheRatingsWithTheImdbLinkBuiltFromTheId() throws Exception {
        Film heat = saved("Heat", "8.3", 84);
        heat.setImdbId("tt0113277");
        save(heat);
        readFromTheDatabase();

        mvc.perform(get("/api/films/{id}", heat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ratings.imdb.score").value(8.3))
            .andExpect(jsonPath("$.ratings.imdb.votes").value(1234567))
            .andExpect(jsonPath("$.ratings.imdb.url").value("https://www.imdb.com/title/tt0113277/"))
            .andExpect(jsonPath("$.ratings.rottenTomatoes.score").value(84))
            .andExpect(jsonPath("$.ratings.rottenTomatoes.url").value("https://www.rottentomatoes.com/m/heat"));
    }

    @Test
    void aSourceWithoutAScoreIsNullAndTheBlockIsStillThere() throws Exception {
        Film film = saved("Unrated", null, null);
        readFromTheDatabase();

        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(jsonPath("$.ratings.imdb").value(nullValue()))
            .andExpect(jsonPath("$.ratings.rottenTomatoes").value(nullValue()));
    }

    @Test
    void theLibraryListsFilmsByImdbScoreHighestFirstAndUnratedLast() throws Exception {
        saved("Mid", "7.0", 10);
        saved("Top", "9.1", null);
        saved("None", null, 99);
        saved("Low", "5.5", 50);
        readFromTheDatabase();

        mvc.perform(get("/api/films").param("sort", "imdb"))
            .andExpect(jsonPath("$.films[*].title", contains("Top", "Mid", "Low", "None")));
    }

    @Test
    void theLibraryListsFilmsByRottenTomatoesScoreHighestFirstAndUnscoredLast() throws Exception {
        saved("Mid", "7.0", 60);
        saved("Top", "9.1", null);
        saved("None", null, 99);
        saved("Low", "5.5", 5);
        readFromTheDatabase();

        mvc.perform(get("/api/films").param("sort", "rt"))
            .andExpect(jsonPath("$.films[*].title", contains("None", "Mid", "Low", "Top")));
    }
}
