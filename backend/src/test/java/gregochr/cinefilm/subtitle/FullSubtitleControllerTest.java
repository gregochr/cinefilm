package gregochr.cinefilm.subtitle;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FullSubtitleController.class)
@Import(SecurityConfig.class)
class FullSubtitleControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FullSubtitleService service;

    @Test
    void servesTheEditionsFullSubtitleAsTextVttInUtf8WithAPrivateNoCacheHeader() throws Exception {
        when(service.vtt(40L)).thenReturn("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCafé\n");

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", 40).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/vtt;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
            .andExpect(content().string("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCafé\n"));
    }

    @Test
    void theVersionParameterIsIgnoredByTheServer() throws Exception {
        when(service.vtt(40L)).thenReturn("WEBVTT\n");

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", 40).param("v", "1700000000000")
                .with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().string("WEBVTT\n"));
        verify(service).vtt(40L);
    }

    @Test
    void anEditionWithNoneIs404WithTheServiceMessage() throws Exception {
        when(service.vtt(40L)).thenThrow(new NotFoundException("No full subtitles"));

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", 40).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No full subtitles"));
    }

    @Test
    void aNonNumericEditionIs400() throws Exception {
        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", "abc").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", 40))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theRouteIsReadOnlyAPostIsRefused() throws Exception {
        mvc.perform(post("/api/editions/{id}/full-subtitles.vtt", 40).with(user("owner")))
            .andExpect(status().is4xxClientError());
    }
}
