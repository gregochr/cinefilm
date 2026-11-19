package gregochr.cinefilm.christmas;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.christmas.ChristmasDtos.ChristmasView;
import gregochr.cinefilm.christmas.ChristmasDtos.FilmState;
import gregochr.cinefilm.christmas.ChristmasDtos.Item;
import gregochr.cinefilm.christmas.ChristmasDtos.Keywords;
import gregochr.cinefilm.christmas.ChristmasDtos.LeftOutItem;
import gregochr.cinefilm.christmas.ChristmasDtos.Limits;
import gregochr.cinefilm.christmas.ChristmasDtos.Reason;
import gregochr.cinefilm.christmas.ChristmasDtos.State;
import gregochr.cinefilm.christmas.ChristmasDtos.WeekDto;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.ChristmasChoice;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChristmasController.class)
@Import(SecurityConfig.class)
class ChristmasControllerTest {

    private static final ChristmasView VIEW = new ChristmasView(
        new WeekDto(LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 20)), new Limits(12, 5),
        new Keywords(3, 4, true),
        List.of(new Item(1L, 11L, "Kept", 1988, "/k.jpg", Reason.KEYWORD),
            new Item(2L, 12L, "Added", null, null, Reason.ADDED)),
        List.of(new Item(2L, 12L, "Added", null, null, Reason.ADDED)),
        List.of(new LeftOutItem(3L, 13L, "Out", 1990, "/o.jpg", true)), true);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChristmasFilms christmas;

    @Test
    void getReturnsTheWholeScreen() throws Exception {
        when(christmas.view()).thenReturn(VIEW);

        mvc.perform(get("/api/admin/christmas").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.week.start").value("2026-12-14"))
            .andExpect(jsonPath("$.week.end").value("2026-12-20"))
            .andExpect(jsonPath("$.limits.max").value(12))
            .andExpect(jsonPath("$.limits.min").value(5))
            .andExpect(jsonPath("$.keywords.fetched").value(3))
            .andExpect(jsonPath("$.keywords.total").value(4))
            .andExpect(jsonPath("$.keywords.running").value(true))
            .andExpect(jsonPath("$.inWeek[0].filmId").value(1))
            .andExpect(jsonPath("$.inWeek[0].tmdbId").value(11))
            .andExpect(jsonPath("$.inWeek[0].title").value("Kept"))
            .andExpect(jsonPath("$.inWeek[0].year").value(1988))
            .andExpect(jsonPath("$.inWeek[0].posterUrl").value("/k.jpg"))
            .andExpect(jsonPath("$.inWeek[0].reason").value("KEYWORD"))
            .andExpect(jsonPath("$.inWeek[1].reason").value("ADDED"))
            .andExpect(jsonPath("$.added[0].filmId").value(2))
            .andExpect(jsonPath("$.leftOut[0].taggedByTmdb").value(true))
            .andExpect(jsonPath("$.fallsBack").value(true));
    }

    @Test
    void getSignedOutIs401() throws Exception {
        mvc.perform(get("/api/admin/christmas"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void putPassesTheTmdbIdAndChoiceAndAnswersTheScreen() throws Exception {
        when(christmas.choose(11L, ChristmasChoice.EXCLUDE)).thenReturn(VIEW);

        mvc.perform(put("/api/admin/christmas/11").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"EXCLUDE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.leftOut[0].filmId").value(3));
        verify(christmas).choose(11L, ChristmasChoice.EXCLUDE);
    }

    @Test
    void aNullChoicePassesNullToForgetTheOverride() throws Exception {
        when(christmas.choose(11L, null)).thenReturn(VIEW);

        mvc.perform(put("/api/admin/christmas/11").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":null}"))
            .andExpect(status().isOk());
        verify(christmas).choose(11L, null);
    }

    @Test
    void anUnknownChoiceIs400() throws Exception {
        mvc.perform(put("/api/admin/christmas/11").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"MAYBE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void aNonNumericTmdbIdIs400() throws Exception {
        mvc.perform(put("/api/admin/christmas/abc").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void putForATmdbIdNoFilmHasIs404() throws Exception {
        when(christmas.choose(99L, ChristmasChoice.INCLUDE))
            .thenThrow(new NotFoundException("No film with TMDB id 99"));

        mvc.perform(put("/api/admin/christmas/99").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No film with TMDB id 99"));
    }

    @Test
    void putDuringAScanIs409WithTheOwnersWords() throws Exception {
        when(christmas.choose(11L, ChristmasChoice.INCLUDE))
            .thenThrow(new ConflictException(ChristmasFilms.SCAN_RUNNING));

        mvc.perform(put("/api/admin/christmas/11").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running — try again when it finishes"));
    }

    @Test
    void putSignedOutIs401AndWithoutTheHeaderIs403() throws Exception {
        mvc.perform(put("/api/admin/christmas/11").header("X-CineFilm", "1").contentType("application/json")
                .content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(put("/api/admin/christmas/11").with(user("owner")).contentType("application/json")
                .content("{\"choice\":\"INCLUDE\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void statesPassesTheIdsAndReturnsEachState() throws Exception {
        when(christmas.states(List.of(1L, 2L, 3L))).thenReturn(List.of(new FilmState(1L, 11L, State.IN_KEYWORD),
            new FilmState(2L, null, State.NO_TMDB_ID)));

        mvc.perform(get("/api/admin/christmas/states").param("filmIds", "1,2,3").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].filmId").value(1))
            .andExpect(jsonPath("$[0].tmdbId").value(11))
            .andExpect(jsonPath("$[0].state").value("IN_KEYWORD"))
            .andExpect(jsonPath("$[1].tmdbId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[1].state").value("NO_TMDB_ID"));
    }

    @Test
    void statesWithNoIdsAsksForNone() throws Exception {
        when(christmas.states(List.of())).thenReturn(List.of());

        mvc.perform(get("/api/admin/christmas/states").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void statesAboveTheCapIs400() throws Exception {
        when(christmas.states(List.of(1L))).thenThrow(new BadRequestException("Ask for at most 50 films at a time"));

        mvc.perform(get("/api/admin/christmas/states").param("filmIds", "1").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Ask for at most 50 films at a time"));
    }

    @Test
    void statesWithANonNumericIdIs400() throws Exception {
        mvc.perform(get("/api/admin/christmas/states").param("filmIds", "1,x").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void statesSignedOutIs401() throws Exception {
        mvc.perform(get("/api/admin/christmas/states").param("filmIds", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
