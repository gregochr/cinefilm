package gregochr.cinefilm.transcode;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.SessionDtos.SessionRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TranscodingAdminController.class)
@Import(SecurityConfig.class)
class TranscodingAdminControllerTest {

    private static final String ID = "5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private Transcoder transcoder;

    @MockitoBean
    private IndexJob indexJob;

    @MockitoBean
    private SessionManager manager;

    @MockitoBean
    private SessionService sessions;

    @Test
    void theStatusIsTheShapeTheAdminPanelReads() throws Exception {
        when(transcoder.mode()).thenReturn(EncoderMode.VAAPI);
        when(indexJob.progress()).thenReturn(new IndexJob.Progress(412, 538));
        when(manager.hlsBytes()).thenReturn(1_288_490_188L);
        when(manager.sessions()).thenReturn(List.of(
            new SessionRow(ID, "Heat", 11, "720p", 3.4, 12, Instant.parse("2026-11-09T19:02:11Z"), false),
            new SessionRow("6b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11", "Alien", 12, "original", null, 0,
                Instant.parse("2026-11-09T19:05:00Z"), true)));

        mvc.perform(get("/api/admin/transcoding").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.encoder").value("vaapi"))
            .andExpect(jsonPath("$.index.indexed").value(412))
            .andExpect(jsonPath("$.index.total").value(538))
            .andExpect(jsonPath("$.hlsBytes").value(1_288_490_188L))
            .andExpect(jsonPath("$.sessions[0].id").value(ID))
            .andExpect(jsonPath("$.sessions[0].filmTitle").value("Heat"))
            .andExpect(jsonPath("$.sessions[0].editionId").value(11))
            .andExpect(jsonPath("$.sessions[0].rung").value("720p"))
            .andExpect(jsonPath("$.sessions[0].speed").value(3.4))
            .andExpect(jsonPath("$.sessions[0].segmentsAhead").value(12))
            .andExpect(jsonPath("$.sessions[0].startedAt").value("2026-11-09T19:02:11Z"))
            .andExpect(jsonPath("$.sessions[0].unavailable").value(false))
            .andExpect(jsonPath("$.sessions[1].speed").doesNotExist())
            .andExpect(jsonPath("$.sessions[1].unavailable").value(true));
    }

    @Test
    void theEncoderIsReportedInLowerCaseForEveryMode() throws Exception {
        when(indexJob.progress()).thenReturn(new IndexJob.Progress(0, 0));
        when(manager.sessions()).thenReturn(List.of());

        when(transcoder.mode()).thenReturn(EncoderMode.SOFTWARE);
        mvc.perform(get("/api/admin/transcoding").with(user("owner")))
            .andExpect(jsonPath("$.encoder").value("software")).andExpect(jsonPath("$.sessions").isEmpty());

        when(transcoder.mode()).thenReturn(EncoderMode.UNAVAILABLE);
        mvc.perform(get("/api/admin/transcoding").with(user("owner")))
            .andExpect(jsonPath("$.encoder").value("unavailable"));
    }

    @Test
    void theStatusNeedsTheSignIn() throws Exception {
        mvc.perform(get("/api/admin/transcoding"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void stopEndsTheSessionAndAnswers204() throws Exception {
        mvc.perform(delete("/api/admin/transcoding/sessions/" + ID).with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNoContent());

        verify(sessions).end(ID);
    }

    @Test
    void stopNeedsTheSignInAndTheCsrfHeader() throws Exception {
        mvc.perform(delete("/api/admin/transcoding/sessions/" + ID).header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(delete("/api/admin/transcoding/sessions/" + ID).with(user("owner")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verify(sessions, never()).end(ID);
    }

    @Test
    void stopOfAMalformedIdIs404() throws Exception {
        doThrow(new NotFoundException("No such session")).when(sessions).end("x");

        mvc.perform(delete("/api/admin/transcoding/sessions/x").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such session"));
    }
}
