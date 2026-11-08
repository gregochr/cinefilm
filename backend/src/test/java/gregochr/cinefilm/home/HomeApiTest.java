package gregochr.cinefilm.home;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.collection.CollectionPart;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HomeApiTest extends ApiTestBase {

    private final Instant now = Instant.now();

    private Edition add(String title, int year, long addedDaysAgo, String... genres) {
        Film film = film(null, title, year, now.minus(Duration.ofDays(addedDaysAgo)), genres);
        Edition edition = edition(film, null, "Theatrical Cut", 100 * MIN, null);
        save(film);
        return edition;
    }

    @Test
    void emptyLibraryHasNoHeroAndEmptyRows() throws Exception {
        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hero").value(nullValue()))
            .andExpect(jsonPath("$.continueWatching", hasSize(0)))
            .andExpect(jsonPath("$.recentlyAdded", hasSize(0)))
            .andExpect(jsonPath("$.notWatchedInYears", hasSize(0)))
            .andExpect(jsonPath("$.collections", hasSize(0)))
            .andExpect(jsonPath("$.weeklyTheme.caption").value("A new theme every Monday"))
            .andExpect(jsonPath("$.weeklyTheme.films", hasSize(0)));
    }

    @Test
    void homeHasHeroRowsThemeAndCollections() throws Exception {
        Edition heat = add("Heat", 1995, 90, "Crime");
        Edition arrival = add("Arrival", 2016, 70, "Sci-fi");
        Edition zodiac = add("Zodiac", 2007, 2, "Crime");
        Edition kes = add("Kes", 1969, 300, "Drama");
        add("Drive", 2011, 40, "Crime");
        saveState(state(heat, 64 * MIN, false, now.minus(Duration.ofHours(2)), null));
        saveState(state(arrival, 55 * MIN, false, now.minus(Duration.ofDays(2)), null));
        saveState(state(kes, 0, true, now.minus(Duration.ofDays(3000)), null));
        saveState(state(zodiac, 0, false, null, null));

        FilmCollection bladeRunner = collection("Blade Runner");
        CollectionPart part = new CollectionPart();
        part.setCollection(bladeRunner);
        part.setTmdbId(335984L);
        part.setTitle("Blade Runner 2049");
        part.setYear(2017);
        part.setReleaseOrder(2);
        bladeRunner.getParts().add(part);
        collections.save(bladeRunner);
        Film br = film(null, "Blade Runner", 1982, now.minus(Duration.ofDays(200)), "Sci-fi");
        br.setTmdbId(78L);
        br.setCollection(bladeRunner);
        edition(br, null, "The Final Cut", 117 * MIN, null);
        save(br);

        mvc.perform(get("/api/home"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hero.title").value("Heat"))
            .andExpect(jsonPath("$.hero.progress.positionMs").value(64 * MIN))
            .andExpect(jsonPath("$.continueWatching[*].title", contains("Arrival")))
            .andExpect(jsonPath("$.recentlyAdded[*].title", contains("Zodiac")))
            .andExpect(jsonPath("$.notWatchedInYears[*].title", contains("Kes")))
            .andExpect(jsonPath("$.notWatchedInYears[0].watched").value(true))
            .andExpect(jsonPath("$.weeklyTheme.title").exists())
            .andExpect(jsonPath("$.weeklyTheme.caption").value("A new theme every Monday"))
            .andExpect(jsonPath("$.weeklyTheme.kind").exists())
            .andExpect(jsonPath("$.weeklyTheme.films").isArray())
            .andExpect(jsonPath("$.collections", hasSize(1)))
            .andExpect(jsonPath("$.collections[0].name").value("Blade Runner"))
            .andExpect(jsonPath("$.collections[0].ownedCount").value(1))
            .andExpect(jsonPath("$.collections[0].totalCount").value(2))
            .andExpect(jsonPath("$.collections[0].posterPaths", hasSize(1)));
    }

    @Test
    void theHeroIsNotRepeatedInContinueWatching() throws Exception {
        Edition a = add("Heat", 1995, 90);
        Edition b = add("Arrival", 2016, 70);
        saveState(state(a, 5 * MIN, false, now.minus(Duration.ofHours(1)), null));
        saveState(state(b, 5 * MIN, false, now.minus(Duration.ofDays(1)), null));
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.hero.title").value("Heat"))
            .andExpect(jsonPath("$.continueWatching", hasSize(1)))
            .andExpect(jsonPath("$.continueWatching[0].title").value("Arrival"));
    }

    @Test
    void collectionsWithoutOwnedFilmsAreNotListed() throws Exception {
        collections.save(collection("Empty"));
        mvc.perform(get("/api/home")).andExpect(jsonPath("$.collections", hasSize(0)));
    }
}
