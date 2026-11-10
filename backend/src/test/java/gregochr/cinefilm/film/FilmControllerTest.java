package gregochr.cinefilm.film;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmDtos.Facets;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FilmController.class)
@Import(SecurityConfig.class)
class FilmControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FilmService service;

    @Test
    void everyQueryParameterReachesTheServiceInItsOwnSlot() throws Exception {
        FilmList empty = new FilmList(0, List.of(), new Facets(List.of(), List.of()));
        when(service.list(new LibraryQuery("heat", "year", "Crime", 1990, "unwatched"))).thenReturn(empty);

        mvc.perform(get("/api/films").with(user("owner"))
                .param("q", "heat").param("sort", "year").param("genre", "Crime")
                .param("decade", "1990").param("show", "unwatched"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void noParametersMeansTheUnfilteredQuery() throws Exception {
        when(service.list(LibraryQuery.all())).thenReturn(
            new FilmList(0, List.of(), new Facets(List.of(), List.of())));

        mvc.perform(get("/api/films").with(user("owner"))).andExpect(status().isOk());

        verify(service).list(LibraryQuery.all());
    }

    @Test
    void detailPassesThePathIdToTheService() throws Exception {
        when(service.detail(7L)).thenThrow(new NotFoundException("No such film"));

        mvc.perform(get("/api/films/{id}", 7).with(user("owner")))
            .andExpect(status().isNotFound())
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
