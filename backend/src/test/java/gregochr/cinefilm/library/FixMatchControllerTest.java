package gregochr.cinefilm.library;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.library.AdminDtos.MatchApplied;
import gregochr.cinefilm.library.AdminDtos.MatchResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FixMatchController.class)
@Import(SecurityConfig.class)
class FixMatchControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FixMatch fixMatch;

    @Test
    void searchReturnsTheResultsAsAList() throws Exception {
        when(fixMatch.search(7L, "The Wicker Man", 1973)).thenReturn(List.of(
            new MatchResultDto(16307, "The Wicker Man", 1973, 93, "/p.jpg", "Robin Hardy",
                List.of("Edward Woodward"))));

        mvc.perform(get("/api/admin/films/7/match").param("query", "The Wicker Man").param("year", "1973")
                .with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].tmdbId").value(16307))
            .andExpect(jsonPath("$[0].runtimeMinutes").value(93))
            .andExpect(jsonPath("$[0].director").value("Robin Hardy"))
            .andExpect(jsonPath("$[0].cast[0]").value("Edward Woodward"));
    }

    @Test
    void searchWithNoYearAsksWithNull() throws Exception {
        when(fixMatch.search(7L, "Dune", null)).thenReturn(List.of());

        mvc.perform(get("/api/admin/films/7/match").param("query", "Dune").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tmdbBeingDownIs503() throws Exception {
        when(fixMatch.search(7L, "Dune", null))
            .thenThrow(new ServiceUnavailableException("TMDB is not available right now", null));

        mvc.perform(get("/api/admin/films/7/match").param("query", "Dune").with(user("owner")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("TMDB is not available right now"));
    }

    @Test
    void applyPassesTheTmdbIdAndReturnsWhatHappened() throws Exception {
        when(fixMatch.apply(7L, 16307L)).thenReturn(new MatchApplied(3L, true));

        mvc.perform(post("/api/admin/films/7/match").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"tmdbId\":16307}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(3))
            .andExpect(jsonPath("$.joined").value(true));
        verify(fixMatch).apply(7L, 16307L);
    }

    @Test
    void applyErrorsMapToTheirStatuses() throws Exception {
        when(fixMatch.apply(7L, 1L)).thenThrow(new ConflictException("A scan is running"));
        when(fixMatch.apply(7L, 2L)).thenThrow(new NotFoundException("No film 7"));
        when(fixMatch.apply(7L, 3L)).thenThrow(new ServiceUnavailableException("TMDB is not available", null));

        for (int i = 1; i <= 3; i++) {
            int expected = new int[] {409, 404, 503}[i - 1];
            mvc.perform(post("/api/admin/films/7/match").with(user("owner")).header("X-CineFilm", "1")
                    .contentType("application/json").content("{\"tmdbId\":" + i + "}"))
                .andExpect(status().is(expected));
        }
    }

    @Test
    void applySignedOutIs401AndWithoutTheHeaderIs403() throws Exception {
        mvc.perform(post("/api/admin/films/7/match").header("X-CineFilm", "1").contentType("application/json")
                .content("{\"tmdbId\":1}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/films/7/match").with(user("owner")).contentType("application/json")
                .content("{\"tmdbId\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void searchSignedOutIs401() throws Exception {
        mvc.perform(get("/api/admin/films/7/match").param("query", "Dune")).andExpect(status().isUnauthorized());
    }
}
