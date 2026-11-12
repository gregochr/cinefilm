package gregochr.cinefilm.subtitle;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.CountsDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ForcedNeedController.class)
@Import(SecurityConfig.class)
class ForcedNeedControllerTest {

    private static final FilmNeedDto TAKEN = new FilmNeedDto(7L, "Taken", 2008, "NEEDED", "OWNER", "NEEDED", "en",
        List.of("en", "fr", "sq"), true);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ForcedNeedService service;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.with(user("owner")).header("X-CineFilm", "1").contentType("application/json");
    }

    @Test
    void theListReturnsEveryFilmWithItsShapeAndTheCounts() throws Exception {
        when(service.list()).thenReturn(new NeedListDto(List.of(TAKEN), new CountsDto(1, 0, 2, 3, 4)));

        mvc.perform(get("/api/admin/forced-subtitles/films").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films.length()").value(1))
            .andExpect(jsonPath("$.films[0].filmId").value(7))
            .andExpect(jsonPath("$.films[0].title").value("Taken"))
            .andExpect(jsonPath("$.films[0].year").value(2008))
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("OWNER"))
            .andExpect(jsonPath("$.films[0].ownerChoice").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].originalLanguage").value("en"))
            .andExpect(jsonPath("$.films[0].spokenLanguages[0]").value("en"))
            .andExpect(jsonPath("$.films[0].spokenLanguages[2]").value("sq"))
            .andExpect(jsonPath("$.films[0].hasForcedSubtitle").value(true))
            .andExpect(jsonPath("$.counts.needed").value(1))
            .andExpect(jsonPath("$.counts.neededMissing").value(0))
            .andExpect(jsonPath("$.counts.unsure").value(2))
            .andExpect(jsonPath("$.counts.unknown").value(3))
            .andExpect(jsonPath("$.counts.notNeeded").value(4));
    }

    @Test
    void theChoicePassesToTheServiceAndTheRowComesBack() throws Exception {
        when(service.choose(7L, new ChoiceRequest("NEEDED"))).thenReturn(TAKEN);
        when(service.choose(7L, new ChoiceRequest(null))).thenReturn(TAKEN);

        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(7))
            .andExpect(jsonPath("$.need").value("NEEDED"))
            .andExpect(jsonPath("$.hasForcedSubtitle").value(true));
        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")).content("{\"choice\":null}"))
            .andExpect(status().isOk());
        verify(service).choose(7L, new ChoiceRequest("NEEDED"));
        verify(service).choose(7L, new ChoiceRequest(null));
    }

    @Test
    void errorsMapTo404ConflictAnd400WithTheirMessages() throws Exception {
        when(service.choose(9L, new ChoiceRequest("NEEDED"))).thenThrow(new NotFoundException("No such film"));
        when(service.choose(7L, new ChoiceRequest("NEEDED"))).thenThrow(new ConflictException("A scan is running"));
        when(service.choose(7L, new ChoiceRequest("MAYBE")))
            .thenThrow(new BadRequestException("The choice must be NEEDED, NOT_NEEDED or null"));

        mvc.perform(signedIn(put("/api/admin/films/9/forced-need")).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such film"));
        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running"));
        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")).content("{\"choice\":\"MAYBE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The choice must be NEEDED, NOT_NEEDED or null"));
    }

    @Test
    void aMalformedBodyOrIdIs400InvalidRequest() throws Exception {
        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")).content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")).content("{\"choice\":[\"NEEDED\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(put("/api/admin/films/abc/forced-need")).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(put("/api/admin/films/7/forced-need")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void bothRoutesAre401SignedOutWithTheJsonBody() throws Exception {
        mvc.perform(get("/api/admin/forced-subtitles/films")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(put("/api/admin/films/7/forced-need").header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theChangeWithoutTheCsrfHeaderIs403() throws Exception {
        mvc.perform(put("/api/admin/films/7/forced-need").with(user("owner"))
                .contentType("application/json").content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
