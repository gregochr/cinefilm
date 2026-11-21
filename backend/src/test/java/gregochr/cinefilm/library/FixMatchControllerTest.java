package gregochr.cinefilm.library;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import gregochr.cinefilm.library.AdminDtos.MatchSearchDto;
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
    void searchReturnsTheResultsTheFileLengthAndTheSuggestion() throws Exception {
        when(fixMatch.search(7L, "The Wicker Man", 1973)).thenReturn(new MatchSearchDto(5_580_000L, 16307L,
            "runtime", List.of(new MatchResultDto(16307, "The Wicker Man", 1973, 93, "/p.jpg", "Robin Hardy",
                List.of("Edward Woodward")))));

        mvc.perform(get("/api/admin/films/7/match").param("query", "The Wicker Man").param("year", "1973")
                .with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileRuntimeMs").value(5_580_000))
            .andExpect(jsonPath("$.suggestedTmdbId").value(16307))
            .andExpect(jsonPath("$.suggestedBecause").value("runtime"))
            .andExpect(jsonPath("$.results[0].tmdbId").value(16307))
            .andExpect(jsonPath("$.results[0].runtimeMinutes").value(93))
            .andExpect(jsonPath("$.results[0].director").value("Robin Hardy"))
            .andExpect(jsonPath("$.results[0].cast[0]").value("Edward Woodward"));
    }

    @Test
    void searchWithNoYearAsksWithNull() throws Exception {
        when(fixMatch.search(7L, "Dune", null)).thenReturn(new MatchSearchDto(null, null, null, List.of()));

        mvc.perform(get("/api/admin/films/7/match").param("query", "Dune").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results.length()").value(0))
            .andExpect(jsonPath("$.fileRuntimeMs").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.suggestedTmdbId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.suggestedBecause").value(org.hamcrest.Matchers.nullValue()));
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

    @Test
    void leaveUnmatchedAnswers204AndPassesTheId() throws Exception {
        mvc.perform(post("/api/admin/films/7/leave-unmatched").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNoContent());
        verify(fixMatch).leaveUnmatched(7L);
    }

    @Test
    void leaveUnmatchedErrorsMapToTheirStatusesAndTexts() throws Exception {
        doThrow(new NotFoundException("No film 7")).when(fixMatch).leaveUnmatched(7L);
        doThrow(new ConflictException("A scan is running")).when(fixMatch).leaveUnmatched(8L);
        doThrow(new ConflictException("This film is not waiting for a match")).when(fixMatch).leaveUnmatched(9L);

        mvc.perform(post("/api/admin/films/7/leave-unmatched").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No film 7"));
        mvc.perform(post("/api/admin/films/8/leave-unmatched").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("A scan is running"));
        mvc.perform(post("/api/admin/films/9/leave-unmatched").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This film is not waiting for a match"));
    }

    @Test
    void leaveUnmatchedSignedOutIs401AndWithoutTheHeaderIs403() throws Exception {
        mvc.perform(post("/api/admin/films/7/leave-unmatched").header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/admin/films/7/leave-unmatched").with(user("owner")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        verifyNoInteractions(fixMatch);
    }

    @Test
    void leftUnmatchedListsTheFilms() throws Exception {
        when(fixMatch.leftUnmatched()).thenReturn(List.of(new AdminDtos.LeftUnmatchedDto(7L, "Home video", 1999),
            new AdminDtos.LeftUnmatchedDto(8L, "Untitled", null)));

        mvc.perform(get("/api/admin/films/left-unmatched").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].title").value("Home video"))
            .andExpect(jsonPath("$[0].year").value(1999))
            .andExpect(jsonPath("$[1].year").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void leftUnmatchedSignedOutIs401() throws Exception {
        mvc.perform(get("/api/admin/films/left-unmatched"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
        verifyNoInteractions(fixMatch);
    }

    @Test
    void putBackAnswers204AndPassesTheId() throws Exception {
        mvc.perform(post("/api/admin/films/7/put-back").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNoContent());
        verify(fixMatch).putBack(7L);
    }

    @Test
    void putBackErrorsMapToTheirStatusesAndTexts() throws Exception {
        doThrow(new NotFoundException("No film 7")).when(fixMatch).putBack(7L);
        doThrow(new ConflictException("A scan is running")).when(fixMatch).putBack(8L);
        doThrow(new ConflictException("This film is not left unmatched")).when(fixMatch).putBack(9L);

        mvc.perform(post("/api/admin/films/7/put-back").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No film 7"));
        mvc.perform(post("/api/admin/films/8/put-back").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("A scan is running"));
        mvc.perform(post("/api/admin/films/9/put-back").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("This film is not left unmatched"));
    }

    @Test
    void putBackSignedOutIs401AndWithoutTheHeaderIs403() throws Exception {
        mvc.perform(post("/api/admin/films/7/put-back").header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/admin/films/7/put-back").with(user("owner")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        verifyNoInteractions(fixMatch);
    }
}
