package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import gregochr.cinefilm.TestData;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.EditionOwner;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Persistence;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dev seed's library on real PostgreSQL: browse, sort ignoring "The", search across title, actor and
 * director, case-insensitivity and the loader path, asserting what the H2 suite asserts.
 */
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LibraryQueriesIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FilmRepository films;

    @Autowired
    private LibraryLoader loader;

    @Autowired
    private EditionRepository editions;

    @Autowired
    private EntityManager em;

    private ResultActions get(String url, String... params) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(url)
            .with(user("owner").roles("OWNER")).header("X-CineFilm", "1");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isOk());
    }

    private Film film(String title) {
        return films.findAll().stream().filter(f -> f.getTitle().equals(title)).findFirst().orElseThrow();
    }

    @Test
    @Transactional
    void theAdminSummaryEditionReadsKeepAnEditionWithNoFilesAndLeaveItsFilmUnloaded() {
        Film bare = TestData.film(null, "Edition without files", 2003, TestData.NOW);
        TestData.edition(bare, null, "Original", 100 * TestData.MIN, null);
        Film saved = films.saveAndFlush(bare);
        long total = editions.count();
        em.clear();

        List<Edition> loaded = editions.findAllWithFilesLeavingFilmLazy();
        List<EditionOwner> owners = editions.findEditionOwners();

        assertThat(loaded).hasSize((int) total);
        assertThat(loaded).anySatisfy(e -> assertThat(e.getMediaFiles()).isEmpty());
        assertThat(loaded).allSatisfy(e -> assertThat(Persistence.getPersistenceUtil().isLoaded(e, "film")).isFalse());
        assertThat(owners).hasSize((int) total);
        Edition empty = loaded.stream().filter(e -> e.getMediaFiles().isEmpty()).findFirst().orElseThrow();
        assertThat(owners).filteredOn(o -> o.editionId().equals(empty.getId())).singleElement()
            .satisfies(o -> assertThat(o.filmId()).isEqualTo(saved.getId()));
    }

    @Test
    void devSeedLoadedTheLibrary() {
        assertThat(films.count()).isGreaterThanOrEqualTo(24);
        Film alien = film("Alien");
        assertThat(films.findByCollectionId(alien.getCollection().getId())).extracting(Film::getTitle)
            .contains("Alien", "Aliens");
    }

    @Test
    @Transactional(readOnly = true)
    void loaderReturnsEveryFilmWithItsPlaybackState() {
        List<FilmView> views = loader.loadAll();
        assertThat(views).hasSize((int) films.count());
        FilmView heat = views.stream().filter(v -> v.film().getTitle().equals("Heat")).findFirst().orElseThrow();
        assertThat(heat.progress()).isPresent();
    }

    @Test
    void browseListsTheWholeLibraryWithFacets() throws Exception {
        get("/api/films")
            .andExpect(jsonPath("$.total", greaterThanOrEqualTo(24)))
            .andExpect(jsonPath("$.facets.genres", hasItem("Crime")))
            .andExpect(jsonPath("$.facets.collections").doesNotExist());
    }

    // The seed has Alien (2 films) and Three Colours (3); Blade Runner has one, so it is not a collection yet.
    @Test
    void collectionsListsTheQualifyingCollectionsAndTheFilmCount() throws Exception {
        get("/api/collections")
            .andExpect(jsonPath("$.filmCount", greaterThanOrEqualTo(24)))
            .andExpect(jsonPath("$.collections", hasSize(2)))
            .andExpect(jsonPath("$.collections[*].name", hasItem("Three Colours")));
    }

    @Test
    void filmCardsCarryTheCollectionIdOnlyForQualifyingCollections() throws Exception {
        get("/api/films", "q", "alien").andExpect(jsonPath("$.films[0].collectionId").isNumber());
        get("/api/films", "q", "heat").andExpect(jsonPath("$.films[0].collectionId").doesNotExist());
    }

    @Test
    void sortByTitleIgnoresALeadingThe() throws Exception {
        String json = get("/api/films", "sort", "title").andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(json, "$.films[*].title");
        // "The Conversation" files under C, between "Chinatown" and "Don't Look Now"; "The Third Man" under T.
        assertThat(titles.indexOf("Chinatown")).isLessThan(titles.indexOf("The Conversation"));
        assertThat(titles.indexOf("The Conversation")).isLessThan(titles.indexOf("Don't Look Now"));
        assertThat(titles.indexOf("Taxi Driver")).isLessThan(titles.indexOf("The Third Man"));
    }

    @Test
    void searchMatchesTitleActorAndDirectorCaseInsensitively() throws Exception {
        get("/api/films", "q", "de niro").andExpect(jsonPath("$.total").value(2));
        get("/api/films", "q", "DE NIRO").andExpect(jsonPath("$.total").value(2));
        get("/api/films", "q", "HEAT").andExpect(jsonPath("$.films[*].title", hasItem("Heat")));
        get("/api/films", "q", "michael mann").andExpect(jsonPath("$.films[*].title", hasItem("Heat")));
        get("/api/films", "q", "zzz").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void filtersByDecade() throws Exception {
        get("/api/films", "decade", "1970").andExpect(jsonPath("$.total", greaterThanOrEqualTo(5)));
    }

    @Test
    void detailCarriesEditionsAndPlaybackState() throws Exception {
        get("/api/films/" + film("Heat").getId())
            .andExpect(jsonPath("$.director").value("Michael Mann"))
            .andExpect(jsonPath("$.editions[0].positionMs").value(3_871_000))
            .andExpect(jsonPath("$.genres", containsInAnyOrder("Crime", "Thriller")));
    }

    @Test
    void homeHasContentInEveryRow() throws Exception {
        get("/api/home")
            .andExpect(jsonPath("$.hero.title").value("Heat"))
            .andExpect(jsonPath("$.continueWatching", hasSize(4)))
            .andExpect(jsonPath("$.collections").doesNotExist());
    }
}
