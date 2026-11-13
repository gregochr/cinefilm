package gregochr.cinefilm.film;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.Facets;
import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import gregochr.cinefilm.film.FilmDtos.Ratings;
import gregochr.cinefilm.web.JsonEtag;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FilmController.class)
@Import({SecurityConfig.class, JsonEtag.class})
class FilmControllerTest {

    private static final String OTHER_TAG = "W/\"fedcba9876543210\"";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FilmService service;

    private static FilmList emptyList() {
        return new FilmList(0, List.of(), new Facets(List.of(), List.of()));
    }

    private void heat() {
        when(service.detail(7L)).thenReturn(new FilmDetail(7L, "Heat", 1995, null, null, false, null, null, null,
            List.of(), null, List.of(), List.of(), List.of(), null, new Ratings(null, null),
            "UNSURE"));
    }

    @Test
    void everyQueryParameterReachesTheServiceInItsOwnSlot() throws Exception {
        when(service.list(new LibraryQuery("heat", "year", "Crime", 1990, "unwatched"))).thenReturn(emptyList());

        mvc.perform(get("/api/films").with(user("owner"))
                .param("q", "heat").param("sort", "year").param("genre", "Crime")
                .param("decade", "1990").param("show", "unwatched"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void noParametersMeansTheUnfilteredQuery() throws Exception {
        when(service.list(LibraryQuery.all())).thenReturn(emptyList());

        mvc.perform(get("/api/films").with(user("owner"))).andExpect(status().isOk());

        verify(service).list(LibraryQuery.all());
    }

    @Test
    void theListCarriesAWeakEtagAndExactlyNoCachePrivate() throws Exception {
        when(service.list(LibraryQuery.all())).thenReturn(emptyList());

        mvc.perform(get("/api/films").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", matchesPattern("W/\"[0-9a-f]{16}\"")))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void theListIsA304ForItsOwnEtagWithNoBodyAndTheSameHeaders() throws Exception {
        when(service.list(LibraryQuery.all())).thenReturn(emptyList());
        String etag = mvc.perform(get("/api/films").with(user("owner")))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/films").with(user("owner")).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void theListGetsTheBodyForADifferentEtag() throws Exception {
        when(service.list(LibraryQuery.all())).thenReturn(emptyList());

        mvc.perform(get("/api/films").with(user("owner")).header("If-None-Match", OTHER_TAG))
            .andExpect(status().isOk())
            .andExpect(content().string(not(emptyString())));
    }

    @Test
    void detailCarriesAWeakEtagAndExactlyNoCachePrivate() throws Exception {
        heat();

        mvc.perform(get("/api/films/{id}", 7).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Heat"))
            .andExpect(header().string("ETag", matchesPattern("W/\"[0-9a-f]{16}\"")))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void detailIsA304ForItsOwnEtagWithNoBodyAndTheSameHeaders() throws Exception {
        heat();
        String etag = mvc.perform(get("/api/films/{id}", 7).with(user("owner")))
            .andReturn().getResponse().getHeader("ETag");

        mvc.perform(get("/api/films/{id}", 7).with(user("owner")).header("If-None-Match", etag))
            .andExpect(status().isNotModified())
            .andExpect(header().string("ETag", etag))
            .andExpect(header().string("Cache-Control", "no-cache, private"))
            .andExpect(header().doesNotExist("Pragma"))
            .andExpect(header().doesNotExist("Expires"))
            .andExpect(content().string(""));
    }

    @Test
    void detailGetsTheBodyForADifferentEtag() throws Exception {
        heat();

        mvc.perform(get("/api/films/{id}", 7).with(user("owner")).header("If-None-Match", OTHER_TAG))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Heat"));
    }

    @Test
    void detailPassesThePathIdToTheServiceAndAMissingFilmIs404WithNoEtagAndNoStore() throws Exception {
        when(service.detail(7L)).thenThrow(new NotFoundException("No such film"));

        mvc.perform(get("/api/films/{id}", 7).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(header().doesNotExist("ETag"))
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.error").value("No such film"));
    }

    @Test
    void aNonNumericIdIsABadRequestWithTheGenericError() throws Exception {
        mvc.perform(get("/api/films/abc").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIsRejectedBeforeTheServiceIsReached() throws Exception {
        mvc.perform(get("/api/films"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
