package gregochr.cinefilm.health;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.health.HealthDtos.PlaybackList;
import gregochr.cinefilm.health.HealthDtos.PlaybackRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(PlaybackHealthController.class)
@Import(SecurityConfig.class)
class PlaybackHealthControllerTest {

    private static final String JSON = "application/json";
    private static final String ID = "3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b";
    private static final String BODY = "{\"seq\":4,\"editionId\":11}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlaybackHealthService health;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(user("owner")).header("X-CineFilm", "1");
    }

    // PUT

    @Test
    void theIdAndTheRawBodyReachTheServiceAndTheAnswerIs204WithNoBody() throws Exception {
        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(BODY))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(health).report(ID, BODY);
    }

    @Test
    void aBodyThatIsNotJsonStillReachesTheServiceAsText() throws Exception {
        doThrow(new BadRequestException("Invalid health report")).when(health).report(ID, "not json");

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void aRefusalFromTheServiceIsA400WithItsMessage() throws Exception {
        doThrow(new BadRequestException("Invalid health report")).when(health).report(ID, BODY);

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void anUnknownEditionIsA404WithTheServicesMessage() throws Exception {
        doThrow(new NotFoundException("No such edition")).when(health).report(ID, BODY);

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(BODY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void aMissingBodyReachesTheServiceAsAnEmptyString() throws Exception {
        doThrow(new BadRequestException("Invalid health report")).when(health).report(ID, "");

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void aBodyOfExactlyTwentyThousandCharactersReachesTheServiceWhole() throws Exception {
        String body = "a".repeat(20_000);

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(body))
            .andExpect(status().isNoContent());

        verify(health).report(ID, body);
    }

    @Test
    void aBodyOfTwentyThousandAndOneCharactersReachesTheServiceAsNullAndIsA400() throws Exception {
        doThrow(new BadRequestException("Invalid health report")).when(health).report(ID, null);

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON)
                .content("a".repeat(20_001)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    @Test
    void nonAsciiTextArrivesIntact() throws Exception {
        String body = "{\"operator\":\"Caf\u00e9 R\u00e9seau \u2014 \u5317\u4eac\"}";

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isNoContent());

        verify(health).report(ID, body);
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(put("/api/playback-health/{id}", ID).header("X-CineFilm", "1").contentType(JSON).content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theMissingCsrfHeaderIsA403() throws Exception {
        mvc.perform(put("/api/playback-health/{id}", ID).with(user("owner")).contentType(JSON).content(BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void whenTwoFirstReportsRaceTheLoserIsAskedAgainWithTheSameArgumentsAndTheAnswerIs204() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate key"))
            .doNothing()
            .when(health).report(ID, BODY);

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(BODY))
            .andExpect(status().isNoContent());

        // Asked once, lost the race, asked once more with the very same arguments.
        verify(health, times(2)).report(ID, BODY);
    }

    @Test
    void aRaceThatFailsAgainIsNotA204() throws Exception {
        doThrow(new DataIntegrityViolationException("duplicate key"))
            .doThrow(new DataIntegrityViolationException("duplicate key again"))
            .when(health).report(ID, BODY);

        assertThatThrownBy(() -> mvc.perform(
                signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(BODY)))
            .hasRootCauseInstanceOf(DataIntegrityViolationException.class)
            .hasRootCauseMessage("duplicate key again");
    }

    @Test
    void aRefusalThatIsNotARaceIsNotRetried() throws Exception {
        doThrow(new BadRequestException("Invalid health report"))
            .doNothing()
            .when(health).report(ID, BODY);

        mvc.perform(signedIn(put("/api/playback-health/{id}", ID)).contentType(JSON).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid health report"));
    }

    // GET

    private static PlaybackRow row() {
        JsonMapper mapper = JsonMapper.builder().build();
        return new PlaybackRow(ID, 7L, "Blade Runner", 11L, Instant.parse("2024-03-31T23:00:00Z"),
            Instant.parse("2024-03-31T23:10:00Z"), mapper.readTree("{\"seq\":4,\"quality\":\"auto\"}"));
    }

    @Test
    void theListIsReturnedAsPlaybacksWithTheReportAsJson() throws Exception {
        when(health.recent()).thenReturn(new PlaybackList(List.of(row())));

        mvc.perform(signedIn(get("/api/admin/playback-health")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbacks.length()").value(1))
            .andExpect(jsonPath("$.playbacks[0].playbackId").value(ID))
            .andExpect(jsonPath("$.playbacks[0].filmId").value(7))
            .andExpect(jsonPath("$.playbacks[0].filmTitle").value("Blade Runner"))
            .andExpect(jsonPath("$.playbacks[0].editionId").value(11))
            .andExpect(jsonPath("$.playbacks[0].startedAt").value("2024-03-31T23:00:00Z"))
            .andExpect(jsonPath("$.playbacks[0].updatedAt").value("2024-03-31T23:10:00Z"))
            .andExpect(jsonPath("$.playbacks[0].report.seq").value(4))
            .andExpect(jsonPath("$.playbacks[0].report.quality").value("auto"));
    }

    @Test
    void oneViewingIsReturnedByItsId() throws Exception {
        when(health.one(ID)).thenReturn(row());

        mvc.perform(signedIn(get("/api/admin/playback-health/{id}", ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playbackId").value(ID))
            .andExpect(jsonPath("$.filmTitle").value("Blade Runner"))
            .andExpect(jsonPath("$.report.seq").value(4));
    }

    @Test
    void aMissingViewingIsA404WithTheServicesMessage() throws Exception {
        when(health.one(ID)).thenThrow(new NotFoundException("No such playback"));

        mvc.perform(signedIn(get("/api/admin/playback-health/{id}", ID)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such playback"));
    }

    @Test
    void theAdminRoutesNeedASession() throws Exception {
        mvc.perform(get("/api/admin/playback-health"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(get("/api/admin/playback-health/{id}", ID))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
