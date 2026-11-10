package gregochr.cinefilm.search;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.person.Person;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

class SearchIndexApiTest extends ApiTestBase {

    @Autowired
    private SearchIndexCache cache;

    @Autowired
    private ApplicationEventPublisher events;

    @BeforeEach
    void freshMemo() {
        // The cache is a singleton shared by every test in this context; start from no memo.
        cache.invalidate();
    }

    private Film add(String title, int year) {
        Film film = film(null, title, year, NOW);
        edition(film, null, "Theatrical Cut", 100 * MIN, null);
        return film;
    }

    private String etag() throws Exception {
        return mvc.perform(get("/api/search-index")).andReturn().getResponse().getHeader("ETag");
    }

    @Test
    void servesOnlyBrowsableFilmsAndTheirPeopleAsCompactJson() throws Exception {
        Person hardy = people.save(new Person("Tom Hardy"));
        Person oconnor = people.save(new Person("Gavin O'Connor"));
        Person ghost = people.save(new Person("Only In A Missing Film"));
        Film warrior = add("Warrior", 2011);
        warrior.setPosterPath("/warrior.jpg");
        credit(warrior, oconnor, CreditRole.DIRECTOR, null, 0);
        credit(warrior, hardy, CreditRole.CAST, "Tommy", 1);
        Film gone = film(null, "Gone", 2003, NOW);
        file(edition(gone, null, "Theatrical Cut", 90 * MIN, null), "gone.m4v").setPresence(Presence.MISSING);
        credit(gone, ghost, CreditRole.CAST, "Nobody", 0);
        save(warrior);
        save(gone);

        mvc.perform(get("/api/search-index"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(jsonPath("$.films", hasSize(1)))
            .andExpect(jsonPath("$.films[0].id").value(warrior.getId()))
            .andExpect(jsonPath("$.films[0].t").value("Warrior"))
            .andExpect(jsonPath("$.films[0].y").value(2011))
            .andExpect(jsonPath("$.films[0].d", contains("Gavin O'Connor")))
            .andExpect(jsonPath("$.films[0].p").value("/warrior.jpg"))
            .andExpect(jsonPath("$.films[0].s").value("warrior"))
            .andExpect(jsonPath("$.films[0].left").doesNotExist())
            .andExpect(jsonPath("$.films[0].at").doesNotExist())
            .andExpect(jsonPath("$.collections", hasSize(0)))
            .andExpect(jsonPath("$.people[*].n", contains("Tom Hardy", "Gavin O'Connor")))
            .andExpect(jsonPath("$.people[0].a").value(1))
            .andExpect(jsonPath("$.people[0].d").value(0))
            .andExpect(jsonPath("$.people[1].a").value(0))
            .andExpect(jsonPath("$.people[1].d").value(1));
    }

    @Test
    void theSecondRequestWithTheEtagIs304AndAScanFinishingWithNewDataChangesTheEtag() throws Exception {
        save(add("Warrior", 2011));
        String before = etag();
        assertThat(before).matches("W/\"[0-9a-f]{16}\"");
        mvc.perform(get("/api/search-index").header("If-None-Match", before))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", before));

        save(add("Hancock", 2008));
        // Not yet invalidated: the memo still answers with the old index.
        assertThat(etag()).isEqualTo(before);
        events.publishEvent(new ScanFinished("admin"));

        String after = etag();
        assertThat(after).isNotEqualTo(before);
        mvc.perform(get("/api/search-index").header("If-None-Match", before))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films", hasSize(2)));
    }

    @Test
    void isA401WhenSignedOut() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/search-index"))
            .andExpect(status().isUnauthorized());
    }
}
