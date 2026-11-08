package gregochr.cinefilm.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(StreamController.class)
@Import(SecurityConfig.class)
class StreamControllerTest {

    @TempDir
    Path dir;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StreamService service;

    @Test
    void servesTheFileTheServiceResolvedWithItsTypeFromTheExtension() throws Exception {
        Path video = Files.writeString(dir.resolve("heat.mp4"), "0123456789");
        when(service.originalPath(5L)).thenReturn(video);

        var result = mvc.perform(get("/api/editions/{id}/stream", 5).with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("0123456789");
    }

    @Test
    void aRangeRequestGetsOnlyThoseBytesAs206() throws Exception {
        Path video = Files.writeString(dir.resolve("heat.mkv"), "0123456789");
        when(service.originalPath(5L)).thenReturn(video);

        var result = mvc.perform(get("/api/editions/{id}/stream", 5).with(user("owner"))
                .header(HttpHeaders.RANGE, "bytes=2-4"))
            .andExpect(status().isPartialContent())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-4/10"))
            .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("234");
    }

    @Test
    void anUnknownExtensionIsServedAsOctetStream() throws Exception {
        Path blob = Files.writeString(dir.resolve("movie.zzzunknown"), "bytes");
        when(service.originalPath(5L)).thenReturn(blob);

        mvc.perform(get("/api/editions/{id}/stream", 5).with(user("owner")))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"));
    }

    @Test
    void aMissingFileIsA404WithTheServiceMessage() throws Exception {
        when(service.originalPath(5L)).thenThrow(new NotFoundException("Media file is not available"));

        mvc.perform(get("/api/editions/{id}/stream", 5).with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Media file is not available"));
    }

    @Test
    void signedOutNeverAsksTheServiceForAPath() throws Exception {
        mvc.perform(get("/api/editions/{id}/stream", 5))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
