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
import gregochr.cinefilm.subtitle.FullNeedDtos.EditionAudioDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedCountsDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullSubtitleStateDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(FullNeedController.class)
@Import(SecurityConfig.class)
class FullNeedControllerTest {

    private static final FullNeedFilmDto AMELIE = new FullNeedFilmDto(7L, "Amelie", 2001, "NEEDED", "OWNER",
        "NEEDED", List.of(new EditionAudioDto("Original", "French"), new EditionAudioDto("Dub", null)),
        new FullSubtitleStateDto("DOWNLOAD"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FullNeedService service;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.with(user("owner")).header("X-CineFilm", "1").contentType("application/json");
    }

    @Test
    void theListReturnsEachFilmWithItsShapeAndTheCounts() throws Exception {
        when(service.list()).thenReturn(new FullNeedListDto(List.of(AMELIE), new FullNeedCountsDto(1, 2, 3, 4)));

        mvc.perform(get("/api/admin/subtitles/full-need").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films.length()").value(1))
            .andExpect(jsonPath("$.films[0].filmId").value(7))
            .andExpect(jsonPath("$.films[0].title").value("Amelie"))
            .andExpect(jsonPath("$.films[0].year").value(2001))
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("OWNER"))
            .andExpect(jsonPath("$.films[0].ownerChoice").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].editions[0].name").value("Original"))
            .andExpect(jsonPath("$.films[0].editions[0].audio").value("French"))
            .andExpect(jsonPath("$.films[0].editions[1].audio").value((Object) null))
            .andExpect(jsonPath("$.films[0].fullSubtitle.source").value("DOWNLOAD"))
            .andExpect(jsonPath("$.counts.needed").value(1))
            .andExpect(jsonPath("$.counts.unsure").value(2))
            .andExpect(jsonPath("$.counts.unknown").value(3))
            .andExpect(jsonPath("$.counts.notNeeded").value(4));
    }

    @Test
    void theChoicePassesToTheServiceAndTheRowComesBack() throws Exception {
        when(service.choose(7L, new ChoiceRequest("NEEDED"))).thenReturn(AMELIE);
        when(service.choose(7L, new ChoiceRequest(null))).thenReturn(AMELIE);

        mvc.perform(signedIn(put("/api/admin/films/7/full-need")).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.filmId").value(7))
            .andExpect(jsonPath("$.need").value("NEEDED"));
        mvc.perform(signedIn(put("/api/admin/films/7/full-need")).content("{\"choice\":null}"))
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

        mvc.perform(signedIn(put("/api/admin/films/9/full-need")).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such film"));
        mvc.perform(signedIn(put("/api/admin/films/7/full-need")).content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running"));
        mvc.perform(signedIn(put("/api/admin/films/7/full-need")).content("{\"choice\":\"MAYBE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The choice must be NEEDED, NOT_NEEDED or null"));
    }

    @Test
    void aMalformedBodyOrIdIs400InvalidRequest() throws Exception {
        mvc.perform(signedIn(put("/api/admin/films/7/full-need")).content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(put("/api/admin/films/7/full-need")).content("{\"choice\":[\"NEEDED\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(put("/api/admin/films/abc/full-need")).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(put("/api/admin/films/7/full-need")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void bothRoutesAre401SignedOutWithTheJsonBody() throws Exception {
        mvc.perform(get("/api/admin/subtitles/full-need")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(put("/api/admin/films/7/full-need").header("X-CineFilm", "1")
                .contentType("application/json").content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theChangeWithoutTheCsrfHeaderIs403() throws Exception {
        mvc.perform(put("/api/admin/films/7/full-need").with(user("owner"))
                .contentType("application/json").content("{\"choice\":\"NEEDED\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
