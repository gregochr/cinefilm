package gregochr.cinefilm.collection;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
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
        Film film = film(null, title, year, NOW);
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
    void theListHasTheFilmCountAndOnlyCollectionsWithTwoOwnedFilmsSortedByName() throws Exception {
        FilmCollection zodiac = collection("Zodiac Saga");
        part(zodiac, 1, "Z1", 2000, 1);
        part(zodiac, 2, "Z2", 2002, 2);
        part(zodiac, 3, "Z3", 2004, 3);
        collections.save(zodiac);
        own(zodiac, 2, "Z2", 2002);
        own(zodiac, 1, "Z1", 2000);
        FilmCollection alien = collections.save(collection("Alien"));
        own(alien, 10, "Alien", 1979);
        own(alien, 11, "Aliens", 1986);
        FilmCollection lone = collections.save(collection("Lone Saga"));
        own(lone, 20, "Lone", 2001);
        // A second film whose only file is missing is not browsable, so it does not count.
        Film gone = film(null, "Gone Sequel", 2003, NOW, "Drama");
        gone.setCollection(lone);
        file(edition(gone, null, "Theatrical Cut", 90 * MIN, null), "gone.m4v").setPresence(Presence.MISSING);
        save(gone);
        collections.save(collection("Empty"));

        mvc.perform(get("/api/collections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmCount").value(5))
            .andExpect(jsonPath("$.collections", hasSize(2)))
            .andExpect(jsonPath("$.collections[*].name", contains("Alien", "Zodiac Saga")))
            .andExpect(jsonPath("$.collections[1].ownedCount").value(2))
            .andExpect(jsonPath("$.collections[1].totalCount").value(3))
            .andExpect(jsonPath("$.collections[1].posterPaths", hasSize(2)))
            .andExpect(jsonPath("$.collections[1].lastAddedAt").value(NOW.toString()));
    }

    @Test
    void theListIsEmptyWithoutCollections() throws Exception {
        mvc.perform(get("/api/collections"))
            .andExpect(jsonPath("$.filmCount").value(0))
            .andExpect(jsonPath("$.collections", hasSize(0)));
    }

    @Test
    void unknownCollectionIs404() throws Exception {
        mvc.perform(get("/api/collections/{id}", 31337))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such collection"));
    }
}
