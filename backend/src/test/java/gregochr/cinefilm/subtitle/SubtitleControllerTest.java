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
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SubtitleController.class)
@Import(SecurityConfig.class)
class SubtitleControllerTest {

    @TempDir
    Path dir;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SubtitleService service;

    @Test
    void servesTheCachedFileAsTextVttInUtf8() throws Exception {
        Path vtt = Files.writeString(dir.resolve("7-3.vtt"), "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCafé\n");
        when(service.vtt(7L, 3)).thenReturn(vtt);

        mvc.perform(get("/api/media-files/{id}/subtitles/{index}.vtt", 7, 3).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/vtt;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
            .andExpect(content().string("WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nCafé\n"));
    }

    @Test
    void anUnknownStreamIs404WithTheServiceMessage() throws Exception {
        when(service.vtt(7L, 9)).thenThrow(new NotFoundException("No such subtitle stream"));

        mvc.perform(get("/api/media-files/{id}/subtitles/{index}.vtt", 7, 9).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle stream"));
    }

    @Test
    void aNonNumericStreamIndexIs400() throws Exception {
        mvc.perform(get("/api/media-files/{id}/subtitles/{index}.vtt", 7, "abc").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/media-files/{id}/subtitles/{index}.vtt", 7, 3))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
