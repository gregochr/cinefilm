package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.search.SearchIndexCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The search index endpoint on PostgreSQL: committed data comes back with an ETag, then a 304. */
@AutoConfigureMockMvc
class SearchIndexIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FilmRepository films;

    @Autowired
    private PersonRepository people;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private FilmPreferenceRepository preferences;

    @Autowired
    private SearchIndexCache cache;

    private long editionId;

    @BeforeEach
    void data() {
        preferences.deleteAll();
        states.deleteAll();
        films.deleteAll();
        people.deleteAll();
        cache.invalidate();
        Person hardy = people.save(new Person("Tom Hardy"));
        Film film = film(null, "Warrior", 2011, NOW);
        file(edition(film, null, "Theatrical Cut", 140 * MIN, null), "/nowhere/warrior.mp4");
        credit(film, hardy, CreditRole.CAST, "Tommy", 0);
        films.save(film);
        editionId = film.getEditions().get(0).getId();
    }

    private ResultActions index(String ifNoneMatch) throws Exception {
        var request = MockMvcRequestBuilders.get("/api/search-index").with(user("owner").roles("OWNER"));
        if (ifNoneMatch != null) {
            request = request.header("If-None-Match", ifNoneMatch);
        }
        return mvc.perform(request);
    }

    @Test
    void servesCommittedDataWithAnEtagThenA304() throws Exception {
        String etag = index(null)
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(jsonPath("$.films", hasSize(1)))
            .andExpect(jsonPath("$.films[0].t").value("Warrior"))
            .andExpect(jsonPath("$.people[0].n").value("Tom Hardy"))
            .andReturn().getResponse().getHeader("ETag");

        assertThat(etag).matches("W/\"[0-9a-f]{16}\"");
        index(etag).andExpect(status().isNotModified()).andExpect(header().string("ETag", etag));
    }

    @Test
    void aCommittedProgressReportChangesTheEtagAndAddsTimeLeft() throws Exception {
        String before = index(null).andReturn().getResponse().getHeader("ETag");

        mvc.perform(MockMvcRequestBuilders.put("/api/editions/" + editionId + "/progress")
                .with(user("owner").roles("OWNER")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"positionMs\":" + 20 * MIN + "}"))
            .andExpect(status().isOk());

        index(before)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films[0].left").value(120 * MIN))
            .andExpect(header().string("ETag", not(before)));
    }
}
