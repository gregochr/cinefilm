package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.person.Person;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilmsApiTest extends ApiTestBase {

    private Film heat;
    private Film thirdMan;
    private Film alien;
    private Film chinatown;
    private Edition heatEdition;
    private FilmCollection alienCollection;

    @BeforeEach
    void data() {
        Instant now = Instant.now();
        Person mann = people.save(new Person("Michael Mann"));
        Person pacino = people.save(new Person("Al Pacino"));
        Person deNiro = people.save(new Person("Robert De Niro"));
        Person reed = people.save(new Person("Carol Reed"));
        Person scott = people.save(new Person("Ridley Scott"));
        alienCollection = collections.save(collection("Alien"));

        heat = film(null, "Heat", 1995, now.minus(Duration.ofDays(3)), "Crime", "Thriller");
        heat.setOverview("Thieves and a cop.");
        heat.setPosterPath("/heat.jpg");
        heat.setBackdropPath("/heat-bg.jpg");
        credit(heat, mann, CreditRole.DIRECTOR, null, 0);
        credit(heat, deNiro, CreditRole.CAST, "Neil McCauley", 2);
        credit(heat, pacino, CreditRole.CAST, "Vincent Hanna", 1);
        heatEdition = edition(heat, null, "Theatrical Cut", 170 * MIN, 160 * MIN);
        file(heatEdition, "/nowhere/heat.mp4");
        save(heat);
        saveState(state(heatEdition, 64 * MIN, false, now.minusSeconds(60), null));

        thirdMan = film(null, "The Third Man", 1949, now.minus(Duration.ofDays(300)), "Thriller");
        credit(thirdMan, reed, CreditRole.DIRECTOR, null, 0);
        Edition thirdManEdition = edition(thirdMan, null, "Theatrical Cut", 104 * MIN, null);
        save(thirdMan);
        saveState(state(thirdManEdition, 0, true, now.minus(Duration.ofDays(5)), now));

        alien = film(null, "Alien", 1979, now.minus(Duration.ofDays(200)), "Sci-fi", "Horror");
        alien.setCollection(alienCollection);
        credit(alien, scott, CreditRole.DIRECTOR, null, 0);
        edition(alien, null, "Theatrical Cut", 117 * MIN, null);
        save(alien);

        chinatown = film(null, "Chinatown", 1974, now.minus(Duration.ofDays(100)), "Crime", "Mystery");
        edition(chinatown, null, "Theatrical Cut", 130 * MIN, null);
        save(chinatown);
    }

    @Test
    void listsTheWholeLibraryNewestAddedFirstWithFacets() throws Exception {
        mvc.perform(get("/api/films"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.films[*].title", contains("Heat", "Chinatown", "Alien", "The Third Man")))
            .andExpect(jsonPath("$.facets.genres",
                contains("Crime", "Horror", "Mystery", "Sci-fi", "Thriller")))
            .andExpect(jsonPath("$.facets.decades", contains(1940, 1970, 1990)))
            .andExpect(jsonPath("$.facets.collections", hasSize(1)))
            .andExpect(jsonPath("$.facets.collections[0].name").value("Alien"))
            .andExpect(jsonPath("$.facets.collections[0].id").value(alienCollection.getId()));
    }

    @Test
    void filmCardHasTheContractFields() throws Exception {
        mvc.perform(get("/api/films").param("q", "heat"))
            .andExpect(jsonPath("$.films[0].id").value(heat.getId()))
            .andExpect(jsonPath("$.films[0].title").value("Heat"))
            .andExpect(jsonPath("$.films[0].year").value(1995))
            .andExpect(jsonPath("$.films[0].posterPath").value("/heat.jpg"))
            .andExpect(jsonPath("$.films[0].backdropPath").value("/heat-bg.jpg"))
            .andExpect(jsonPath("$.films[0].watched").value(false))
            .andExpect(jsonPath("$.films[0].director").value("Michael Mann"))
            .andExpect(jsonPath("$.films[0].progress.positionMs").value(64 * MIN))
            .andExpect(jsonPath("$.films[0].progress.runtimeMs").value(170 * MIN))
            .andExpect(jsonPath("$.films[0].progress.editionId").value(heatEdition.getId()));
    }

    @Test
    void cardWithoutProgressHasNullProgressAndNullDirectorWhenUncredited() throws Exception {
        mvc.perform(get("/api/films").param("q", "chinatown"))
            .andExpect(jsonPath("$.films[0].progress").value(nullValue()))
            .andExpect(jsonPath("$.films[0].director").value(nullValue()))
            .andExpect(jsonPath("$.films[0].posterPath").value(nullValue()));
    }

    @Test
    void searchMatchesTitleActorAndDirector() throws Exception {
        mvc.perform(get("/api/films").param("q", "de niro"))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.films[0].title").value("Heat"));
        mvc.perform(get("/api/films").param("q", "SCOTT"))
            .andExpect(jsonPath("$.films[0].title").value("Alien"));
        mvc.perform(get("/api/films").param("q", "third"))
            .andExpect(jsonPath("$.films[0].title").value("The Third Man"));
        mvc.perform(get("/api/films").param("q", "zzz"))
            .andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.films", hasSize(0)))
            .andExpect(jsonPath("$.facets.genres", hasSize(5)));
    }

    @Test
    void sortsByTitleIgnoringTheYearAndAdded() throws Exception {
        mvc.perform(get("/api/films").param("sort", "title"))
            .andExpect(jsonPath("$.films[*].title", contains("Alien", "Chinatown", "Heat", "The Third Man")));
        mvc.perform(get("/api/films").param("sort", "year"))
            .andExpect(jsonPath("$.films[*].year", contains(1949, 1974, 1979, 1995)));
        mvc.perform(get("/api/films").param("sort", "added"))
            .andExpect(jsonPath("$.films[0].title").value("Heat"));
    }

    @Test
    void filtersByGenreDecadeCollectionAndShow() throws Exception {
        mvc.perform(get("/api/films").param("genre", "Crime"))
            .andExpect(jsonPath("$.films[*].title", containsInAnyOrder("Heat", "Chinatown")));
        mvc.perform(get("/api/films").param("decade", "1970"))
            .andExpect(jsonPath("$.films[*].title", containsInAnyOrder("Chinatown", "Alien")));
        mvc.perform(get("/api/films").param("collection", String.valueOf(alienCollection.getId())))
            .andExpect(jsonPath("$.films[*].title", contains("Alien")));
        mvc.perform(get("/api/films").param("show", "watched"))
            .andExpect(jsonPath("$.films[*].title", contains("The Third Man")));
        mvc.perform(get("/api/films").param("show", "unwatched"))
            .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/films").param("genre", "Thriller").param("show", "unwatched"))
            .andExpect(jsonPath("$.films[*].title", contains("Heat")));
    }

    @Test
    void invalidQueryValuesAreBadRequests() throws Exception {
        mvc.perform(get("/api/films").param("sort", "popularity")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/films").param("show", "seen")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/films").param("decade", "seventies")).andExpect(status().isBadRequest());
    }

    @Test
    void detailHasEveryContractField() throws Exception {
        mvc.perform(get("/api/films/{id}", heat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(heat.getId()))
            .andExpect(jsonPath("$.title").value("Heat"))
            .andExpect(jsonPath("$.year").value(1995))
            .andExpect(jsonPath("$.overview").value("Thieves and a cop."))
            .andExpect(jsonPath("$.genres", contains("Crime", "Thriller")))
            .andExpect(jsonPath("$.collection").value(nullValue()))
            .andExpect(jsonPath("$.watched").value(false))
            .andExpect(jsonPath("$.progress.positionMs").value(64 * MIN))
            .andExpect(jsonPath("$.director").value("Michael Mann"))
            .andExpect(jsonPath("$.directors[0].name").value("Michael Mann"))
            .andExpect(jsonPath("$.directors[0].id").exists())
            .andExpect(jsonPath("$.directors[0].profilePath").value(nullValue()))
            .andExpect(jsonPath("$.cast[*].person.name", contains("Al Pacino", "Robert De Niro")))
            .andExpect(jsonPath("$.cast[1].character").value("Neil McCauley"))
            .andExpect(jsonPath("$.editions", hasSize(1)))
            .andExpect(jsonPath("$.editions[0].id").value(heatEdition.getId()))
            .andExpect(jsonPath("$.editions[0].name").value("Theatrical Cut"))
            .andExpect(jsonPath("$.editions[0].year").value(1995))
            .andExpect(jsonPath("$.editions[0].runtimeMs").value(170 * MIN))
            .andExpect(jsonPath("$.editions[0].note").value(nullValue()))
            .andExpect(jsonPath("$.editions[0].creditsMarkerMs").value(160 * MIN))
            .andExpect(jsonPath("$.editions[0].positionMs").value(64 * MIN))
            .andExpect(jsonPath("$.editions[0].watched").value(false))
            .andExpect(jsonPath("$.editions[0].file.videoCodec").value("HEVC"))
            .andExpect(jsonPath("$.editions[0].file.height").value(1080))
            .andExpect(jsonPath("$.editions[0].file.audioSummary").value("5.1"))
            .andExpect(jsonPath("$.editions[0].file.addedAt").exists())
            .andExpect(jsonPath("$.editions[0].audioStreams", hasSize(2)))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].id").exists())
            .andExpect(jsonPath("$.editions[0].audioStreams[0].index").value(0))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].language").value("English"))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].channels").value("5.1"))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].codec").value("DTS"))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].isDefault").value(true))
            .andExpect(jsonPath("$.editions[0].audioStreams[0].safariPlayable").value(true))
            .andExpect(jsonPath("$.editions[0].audioStreams[1].title").value("Commentary — Someone"))
            .andExpect(jsonPath("$.editions[0].subtitleStreams[0].language").value("English"))
            .andExpect(jsonPath("$.editions[0].subtitleStreams[0].forced").value(false))
            .andExpect(jsonPath("$.editions[0].subtitleStreams[0].format").value("SRT"))
            .andExpect(jsonPath("$.editions[0].qualities[*].id",
                contains("auto", "original", "1080p", "720p", "480p")))
            .andExpect(jsonPath("$.editions[0].qualities[*].available",
                contains(true, true, false, false, false)))
            .andExpect(jsonPath("$.editions[0].qualities[1].bitrate").value(9_000_000))
            .andExpect(jsonPath("$.editions[0].qualities[4].premade").value(false))
            .andExpect(jsonPath("$.preferences.editionId").value(heatEdition.getId()))
            .andExpect(jsonPath("$.preferences.quality").value("auto"))
            .andExpect(jsonPath("$.preferences.audioStreamId").exists())
            .andExpect(jsonPath("$.preferences.subtitle").value("off"));
    }

    @Test
    void detailShowsCollectionAndEditionWithoutFile() throws Exception {
        mvc.perform(get("/api/films/{id}", alien.getId()))
            .andExpect(jsonPath("$.collection.id").value(alienCollection.getId()))
            .andExpect(jsonPath("$.collection.name").value("Alien"))
            .andExpect(jsonPath("$.editions[0].file").value(nullValue()))
            .andExpect(jsonPath("$.editions[0].audioStreams", hasSize(0)))
            .andExpect(jsonPath("$.editions[0].qualities[1].bitrate").value(nullValue()))
            .andExpect(jsonPath("$.preferences.audioStreamId").value(nullValue()));
    }

    @Test
    void detailOfAWatchedFilmIsWatched() throws Exception {
        mvc.perform(get("/api/films/{id}", thirdMan.getId()))
            .andExpect(jsonPath("$.watched").value(true))
            .andExpect(jsonPath("$.progress").value(nullValue()))
            .andExpect(jsonPath("$.editions[0].watched").value(true));
    }

    @Test
    void unknownFilmIs404() throws Exception {
        mvc.perform(get("/api/films/{id}", 999_999))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/films/abc")).andExpect(status().isBadRequest());
    }
}
