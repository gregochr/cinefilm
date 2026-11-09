package gregochr.cinefilm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.FilmRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Boots with the dev profile against the real (non-rolled-back) database and checks the seed feeds every screen. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"dev", "test"})
class DevSeedTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmCollectionRepository collections;

    @Autowired
    private DevSeed seed;

    private MvcResult json(String url) throws Exception {
        return mvc.perform(get(url).with(user("owner"))).andExpect(status().isOk()).andReturn();
    }

    @Test
    void homeHasContentInEveryRow() throws Exception {
        mvc.perform(get("/api/home").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hero.title").value("Heat"))
            .andExpect(jsonPath("$.hero.progress.positionMs").value(3_871_000))
            .andExpect(jsonPath("$.continueWatching", hasSize(4)))
            .andExpect(jsonPath("$.recentlyAdded.length()", greaterThanOrEqualTo(5)))
            .andExpect(jsonPath("$.notWatchedInYears.length()", greaterThanOrEqualTo(5)))
            .andExpect(jsonPath("$.weeklyTheme.films.length()", greaterThanOrEqualTo(5)))
            .andExpect(jsonPath("$.weeklyTheme.title").value(org.hamcrest.Matchers.startsWith("This week: ")))
            .andExpect(jsonPath("$.collections").doesNotExist());
    }

    @Test
    void libraryHasAtLeastFiveFilmsFromTheNineteenSeventies() throws Exception {
        mvc.perform(get("/api/films").param("decade", "1970").with(user("owner")))
            .andExpect(jsonPath("$.total", greaterThanOrEqualTo(5)));
        assertThat(films.count()).isGreaterThanOrEqualTo(24);
    }

    @Test
    void bladeRunnerHasThreeEditionsAndHeatHasTheCommentaryTrack() throws Exception {
        String list = json("/api/films?q=blade").getResponse().getContentAsString();
        assertThat(list).contains("Blade Runner");
        long brId = films.findAll().stream().filter(f -> f.getTitle().equals("Blade Runner")).findFirst()
            .orElseThrow().getId();
        mvc.perform(get("/api/films/{id}", brId).with(user("owner")))
            .andExpect(jsonPath("$.editions", hasSize(3)))
            .andExpect(jsonPath("$.editions[*].name", hasItem("The Final Cut")))
            .andExpect(jsonPath("$.editions[*].name", hasItem("Director\u2019s Cut")))
            .andExpect(jsonPath("$.editions[*].note", hasItem("No voice-over")))
            // Only one Blade Runner film is seeded, so it is not yet a collection.
            .andExpect(jsonPath("$.collection").value(org.hamcrest.Matchers.nullValue()));

        long heatId = films.findAll().stream().filter(f -> f.getTitle().equals("Heat")).findFirst()
            .orElseThrow().getId();
        mvc.perform(get("/api/films/{id}", heatId).with(user("owner")))
            .andExpect(jsonPath("$.director").value("Michael Mann"))
            .andExpect(jsonPath("$.editions[0].audioStreams[*].title", hasItem("Commentary \u2014 Michael Mann")))
            .andExpect(jsonPath("$.editions[0].subtitleStreams", hasSize(2)))
            .andExpect(jsonPath("$.editions[0].positionMs").value(3_871_000))
            .andExpect(jsonPath("$.cast[*].character", hasItem("Neil McCauley")));
    }

    @Test
    void personAndCollectionPagesWork() throws Exception {
        mvc.perform(get("/api/films").param("q", "de niro").with(user("owner")))
            .andExpect(jsonPath("$.total").value(2));
        long alienCollection = collections.findAll().stream().filter(c -> c.getName().equals("Alien"))
            .findFirst().orElseThrow().getId();
        mvc.perform(get("/api/collections/{id}", alienCollection).with(user("owner")))
            .andExpect(jsonPath("$.parts", hasSize(4)))
            .andExpect(jsonPath("$.parts[2].owned").value(false));
    }

    @Test
    void seedingAgainIsANoOp() {
        long before = films.count();
        seed.run(org.mockito.Mockito.mock(ApplicationArguments.class));
        assertThat(films.count()).isEqualTo(before);
    }
}
