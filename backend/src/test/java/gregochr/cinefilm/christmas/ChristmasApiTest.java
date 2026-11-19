package gregochr.cinefilm.christmas;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.ChristmasChoice;
import gregochr.cinefilm.film.ChristmasOverrides;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.library.LibraryLock;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** The Christmas films API through the whole application, against what Home shows for the same library. */
class ChristmasApiTest extends ApiTestBase {

    @MockitoSpyBean
    private Clock clock;

    @Autowired
    private ChristmasOverrides overrides;

    @Autowired
    private LibraryLock lock;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void christmasWeek() {
        doReturn(Instant.parse("2026-12-14T09:00:00Z")).when(clock).instant();
    }

    @AfterEach
    void releaseTheLock() {
        lock.release();
    }

    private Film add(String title, Integer year, Long tmdbId, String... keywords) {
        Film film = film(null, title, year, NOW, "Comedy");
        film.setTmdbId(tmdbId);
        film.getKeywords().addAll(List.of(keywords));
        edition(film, null, "Theatrical Cut", 100 * MIN, null);
        return save(film);
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private void putChoice(long tmdbId, String choice) throws Exception {
        mvc.perform(put("/api/admin/christmas/{id}", tmdbId).content("{\"choice\":" + choice + "}"))
            .andExpect(status().isOk());
        reload();
    }

    @Test
    void theListIsHomesRowFollowedByTheFilmsHomeCutAfterTwelve() throws Exception {
        for (int i = 0; i < 14; i++) {
            add("Tagged " + (char) ('A' + i), 1950 + i % 3, 100L + i, "christmas");
        }
        add("Added", 1949, 900L);
        add("Left out", 1940, 901L, "christmas");
        overrides.set(900L, ChristmasChoice.INCLUDE);
        overrides.set(901L, ChristmasChoice.EXCLUDE);
        reload();

        String home = mvc.perform(get("/api/home")).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        String christmas = mvc.perform(get("/api/admin/christmas")).andExpect(status().isOk()).andReturn()
            .getResponse().getContentAsString();

        List<String> homeTitles = JsonPath.read(home, "$.weeklyTheme.films[*].title");
        List<String> listTitles = JsonPath.read(christmas, "$.inWeek[*].title");
        assertThat(homeTitles).hasSize(12);
        assertThat(listTitles).hasSize(15).startsWith(homeTitles.toArray(String[]::new));
        assertThat(listTitles).doesNotContain("Left out");
        assertThat(listTitles.get(0)).isEqualTo("Added");
    }

    @Test
    void getAnswersWeekLimitsKeywordsAndTheThreeLists() throws Exception {
        add("Tagged", 1988, 1L, "christmas");
        Film fetched = add("Fetched", 1990, 2L);
        fetched.setKeywordsFetchedAt(NOW);
        add("No id", 1991, null);
        add("Added", 1950, 900L);
        add("Left out", 1960, 901L, "christmas");
        overrides.set(900L, ChristmasChoice.INCLUDE);
        overrides.set(901L, ChristmasChoice.EXCLUDE);
        reload();

        mvc.perform(get("/api/admin/christmas"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.week.start").value("2026-12-14"))
            .andExpect(jsonPath("$.week.end").value("2026-12-20"))
            .andExpect(jsonPath("$.limits.max").value(12))
            .andExpect(jsonPath("$.limits.min").value(5))
            .andExpect(jsonPath("$.keywords.total").value(4))
            .andExpect(jsonPath("$.keywords.fetched").value(1))
            .andExpect(jsonPath("$.keywords.running").value(false))
            .andExpect(jsonPath("$.inWeek[*].title", contains("Added", "Tagged")))
            .andExpect(jsonPath("$.inWeek[0].reason").value("ADDED"))
            .andExpect(jsonPath("$.inWeek[1].reason").value("KEYWORD"))
            .andExpect(jsonPath("$.added[*].title", contains("Added")))
            .andExpect(jsonPath("$.leftOut[*].title", contains("Left out")))
            .andExpect(jsonPath("$.leftOut[0].taggedByTmdb").value(true))
            .andExpect(jsonPath("$.fallsBack").value(true));
    }

    @Test
    void puttingAndForgettingAChoiceMovesAFilmBetweenTheLists() throws Exception {
        add("Plain", 1970, 5L);

        mvc.perform(put("/api/admin/christmas/5").content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.added[*].filmId", hasSize(1)))
            .andExpect(jsonPath("$.inWeek[0].title").value("Plain"));
        assertThat(overrides.includes()).containsExactly(5L);

        mvc.perform(put("/api/admin/christmas/5").content("{\"choice\":\"EXCLUDE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.added", hasSize(0)))
            .andExpect(jsonPath("$.leftOut[0].title").value("Plain"))
            .andExpect(jsonPath("$.leftOut[0].taggedByTmdb").value(false));
        assertThat(overrides.includes()).isEmpty();
        assertThat(overrides.excludes()).containsExactly(5L);

        mvc.perform(put("/api/admin/christmas/5").content("{\"choice\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.leftOut", hasSize(0)))
            .andExpect(jsonPath("$.inWeek", hasSize(0)));
        assertThat(overrides.excludes()).isEmpty();
    }

    @Test
    void anIncludeRowForAFilmTmdbTagsIsDeletedByTheNextWrite() throws Exception {
        add("Tagged later", 1970, 5L, "christmas");
        add("Other", 1971, 6L);
        overrides.set(5L, ChristmasChoice.INCLUDE);
        reload();

        putChoice(6L, "\"INCLUDE\"");

        assertThat(overrides.includes()).containsExactly(6L);
    }

    @Test
    void aTmdbIdNoFilmHasIs404AndChangesNothing() throws Exception {
        mvc.perform(put("/api/admin/christmas/404").content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No film with TMDB id 404"));
        assertThat(overrides.includes()).isEmpty();
    }

    @Test
    void anUnknownChoiceIs400() throws Exception {
        add("Plain", 1970, 5L);

        mvc.perform(put("/api/admin/christmas/5").content("{\"choice\":\"MAYBE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        assertThat(overrides.includes()).isEmpty();
    }

    @Test
    void aScanRunningRefusesAChangeAndLeavesTheTableAlone() throws Exception {
        add("Plain", 1970, 5L);
        lock.tryAcquire(LibraryLock.SCAN);

        mvc.perform(put("/api/admin/christmas/5").content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running — try again when it finishes"));
        assertThat(overrides.includes()).isEmpty();
    }

    @Test
    void statesNameEachFilmsPlace() throws Exception {
        Film keyword = add("Keyword", 1970, 1L, "christmas");
        Film added = add("Added", 1971, 2L);
        Film leftOut = add("Left out", 1972, 3L, "christmas");
        Film pending = add("Pending", 1973, 4L);
        Film notIn = add("Not in", 1974, 5L);
        notIn.setKeywordsFetchedAt(NOW);
        Film noId = add("No id", 1975, null);
        overrides.set(2L, ChristmasChoice.INCLUDE);
        overrides.set(3L, ChristmasChoice.EXCLUDE);
        reload();
        String ids = String.join(",", Set.of(keyword, added, leftOut, pending, notIn, noId).stream()
            .map(f -> String.valueOf(f.getId())).sorted().toList());

        mvc.perform(get("/api/admin/christmas/states?filmIds=" + ids + ",99999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(6)))
            .andExpect(jsonPath("$[?(@.filmId==" + keyword.getId() + ")].state").value("IN_KEYWORD"))
            .andExpect(jsonPath("$[?(@.filmId==" + added.getId() + ")].state").value("IN_ADDED"))
            .andExpect(jsonPath("$[?(@.filmId==" + leftOut.getId() + ")].state").value("LEFT_OUT"))
            .andExpect(jsonPath("$[?(@.filmId==" + pending.getId() + ")].state").value("KEYWORDS_PENDING"))
            .andExpect(jsonPath("$[?(@.filmId==" + notIn.getId() + ")].state").value("NOT_IN"))
            .andExpect(jsonPath("$[?(@.filmId==" + noId.getId() + ")].state").value("NO_TMDB_ID"))
            .andExpect(jsonPath("$[?(@.filmId==" + noId.getId() + ")].tmdbId").value(
                org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void fiftyOneIdsIs400() throws Exception {
        String ids = String.join(",", java.util.stream.LongStream.rangeClosed(1, 51).mapToObj(String::valueOf)
            .toList());

        mvc.perform(get("/api/admin/christmas/states?filmIds=" + ids))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Ask for at most 50 films at a time"));
    }
}
