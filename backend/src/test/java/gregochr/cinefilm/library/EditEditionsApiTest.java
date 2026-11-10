package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Edit editions end to end: JSON in and out, the service, JPA and the library lock. */
class EditEditionsApiTest extends ApiTestBase {

    @Autowired
    private LibraryLock lock;

    @AfterEach
    void releaseTheLock() {
        lock.release();
    }

    private Film towers() {
        Film built = film(null, "The Two Towers", 2002, NOW);
        file(edition(built, null, "Original", 114 * MIN, null), "T/Towers {edition-Extended Edition} - pt1.m4v");
        file(edition(built, null, "Original", 121 * MIN, null), "T/Towers {edition-Extended Edition} - pt2.m4v");
        return save(built);
    }

    private static String body(Film film) {
        return "{\"editions\":[{\"id\":" + film.getEditions().get(0).getId() + ",\"name\":\"Extended Edition\","
            + "\"default\":true,\"files\":[{\"fileId\":" + film.getEditions().get(0).getMediaFiles().get(0).getId()
            + ",\"part\":1},{\"fileId\":" + film.getEditions().get(1).getMediaFiles().get(0).getId()
            + ",\"part\":2}]}],\"notThisFilm\":[]}";
    }

    @Test
    void theSuggestionIsLoadedThenSavedAndTheFilmReadsBackAsArranged() throws Exception {
        Film film = towers();

        mvc.perform(get("/api/admin/films/{id}/editions", film.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files.length()").value(2))
            .andExpect(jsonPath("$.editions[0].default").value(true))
            .andExpect(jsonPath("$.suggestion.reason").value("From the edition tags and part numbers"))
            .andExpect(jsonPath("$.suggestion.editions[0].name").value("Extended Edition"))
            .andExpect(jsonPath("$.suggestion.editions[0].files[1].part").value(2));

        mvc.perform(put("/api/admin/films/{id}/editions", film.getId()).content(body(film)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(film.getId()))
            .andExpect(jsonPath("$.newFilmIds.length()").value(0));

        mvc.perform(get("/api/admin/films/{id}/editions", film.getId()))
            .andExpect(jsonPath("$.suggestion.reason").value("As you last arranged it"))
            .andExpect(jsonPath("$.editions.length()").value(1))
            .andExpect(jsonPath("$.editions[0].name").value("Extended Edition"))
            .andExpect(jsonPath("$.files[0].partNumber").value(1))
            .andExpect(jsonPath("$.files[1].partNumber").value(2));
        assertThat(films.findById(film.getId()).orElseThrow().isEditionsConfirmed()).isTrue();
    }

    @Test
    void aBrokenArrangementComesBackAs400WithFieldErrors() throws Exception {
        Film film = towers();
        String broken = body(film).replace("\"part\":2", "\"part\":3");

        mvc.perform(put("/api/admin/films/{id}/editions", film.getId()).content(broken))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Parts 1 and 3 — part 2 is missing."))
            .andExpect(jsonPath("$.errors[0].field").value("editions[0].parts"))
            .andExpect(jsonPath("$.errors[0].message").value("Parts 1 and 3 — part 2 is missing."));
    }

    @Test
    void aScanInTheWayIs409AndAnUnknownFilmIs404() throws Exception {
        Film film = towers();
        lock.tryAcquire(LibraryLock.SCAN);

        mvc.perform(put("/api/admin/films/{id}/editions", film.getId()).content(body(film)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running"));
        lock.release();
        mvc.perform(get("/api/admin/films/9999/editions"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No film 9999"));
    }
}
