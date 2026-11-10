package gregochr.cinefilm.subtitle;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(EditionSubtitleController.class)
@Import(SecurityConfig.class)
class EditionSubtitleControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EditionSubtitleService service;

    @Test
    void servesTheEditionsSubtitlesAsTextVttInUtf8() throws Exception {
        when(service.vtt(40L, 3)).thenReturn("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCafé\n");

        mvc.perform(get("/api/editions/{id}/subtitles/{index}.vtt", 40, 3).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/vtt;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
            .andExpect(content().string("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCafé\n"));
    }

    @Test
    void anUnknownStreamIs404WithTheServiceMessage() throws Exception {
        when(service.vtt(40L, 9)).thenThrow(new NotFoundException("No such subtitle stream"));

        mvc.perform(get("/api/editions/{id}/subtitles/{index}.vtt", 40, 9).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle stream"));
    }

    @Test
    void aNonNumericStreamIndexIs400() throws Exception {
        mvc.perform(get("/api/editions/{id}/subtitles/{index}.vtt", 40, "abc").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/editions/{id}/subtitles/{index}.vtt", 40, 3))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
