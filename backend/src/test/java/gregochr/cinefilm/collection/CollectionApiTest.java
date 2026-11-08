package gregochr.cinefilm.collection;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CollectionApiTest extends ApiTestBase {

    private static CollectionPart part(FilmCollection owner, long tmdbId, String title, int year, int order) {
        CollectionPart part = new CollectionPart();
        part.setCollection(owner);
        part.setTmdbId(tmdbId);
        part.setTitle(title);
        part.setYear(year);
        part.setReleaseOrder(order);
        part.setPosterPath("/" + tmdbId + ".jpg");
        owner.getParts().add(part);
        return part;
    }

    private Film own(FilmCollection owner, long tmdbId, String title, int year) {
        Film film = film(null, title, year, Instant.now());
        film.setTmdbId(tmdbId);
        film.setCollection(owner);
        edition(film, null, "Theatrical Cut", 100 * MIN, null);
        return save(film);
    }

    @Test
    void ownedFilmsAndMissingPartsInReleaseOrder() throws Exception {
        FilmCollection alien = collection("Alien");
        alien.setPosterPath("/alien-collection.jpg");
        // Inserted out of order on purpose: the page must follow releaseOrder.
        part(alien, 8077, "Alien³", 1992, 3);
        part(alien, 348, "Alien", 1979, 1);
        part(alien, 8078, "Alien Resurrection", 1997, 4);
        part(alien, 679, "Aliens", 1986, 2);
        collections.save(alien);
        own(alien, 679, "Aliens", 1986);
        own(alien, 348, "Alien", 1979);

        mvc.perform(get("/api/collections/{id}", alien.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(alien.getId()))
            .andExpect(jsonPath("$.name").value("Alien"))
            .andExpect(jsonPath("$.posterPath").value("/alien-collection.jpg"))
            .andExpect(jsonPath("$.parts", hasSize(4)))
            .andExpect(jsonPath("$.parts[0].owned").value(true))
            .andExpect(jsonPath("$.parts[0].film.title").value("Alien"))
            .andExpect(jsonPath("$.parts[1].owned").value(true))
            .andExpect(jsonPath("$.parts[1].film.title").value("Aliens"))
            .andExpect(jsonPath("$.parts[2].owned").value(false))
            .andExpect(jsonPath("$.parts[2].title").value("Alien³"))
            .andExpect(jsonPath("$.parts[2].year").value(1992))
            .andExpect(jsonPath("$.parts[2].posterPath").value("/8077.jpg"))
            .andExpect(jsonPath("$.parts[2].film").doesNotExist())
            .andExpect(jsonPath("$.parts[3].title").value("Alien Resurrection"));
    }

    @Test
    void ownedFilmWithoutAMatchingPartIsAppendedAfterTheParts() throws Exception {
        FilmCollection trilogy = collection("Trilogy");
        part(trilogy, 1, "One", 2000, 1);
        collections.save(trilogy);
        own(trilogy, 999, "Unmatched Spin-off", 2005);

        mvc.perform(get("/api/collections/{id}", trilogy.getId()))
            .andExpect(jsonPath("$.parts", hasSize(2)))
            .andExpect(jsonPath("$.parts[0].owned").value(false))
            .andExpect(jsonPath("$.parts[0].title").value("One"))
            .andExpect(jsonPath("$.parts[1].owned").value(true))
            .andExpect(jsonPath("$.parts[1].film.title").value("Unmatched Spin-off"));
    }

    @Test
    void collectionWithoutPartsListsOwnedFilmsByYear() throws Exception {
        FilmCollection loose = collection("Loose");
        collections.save(loose);
        own(loose, 0, "Later", 2010);
        own(loose, 0, "Earlier", 1990);

        mvc.perform(get("/api/collections/{id}", loose.getId()))
            .andExpect(jsonPath("$.posterPath").value(nullValue()))
            .andExpect(jsonPath("$.parts[*].film.title", contains("Earlier", "Later")));
    }

    @Test
    void unknownCollectionIs404() throws Exception {
        mvc.perform(get("/api/collections/{id}", 31337))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").exists());
    }
}
