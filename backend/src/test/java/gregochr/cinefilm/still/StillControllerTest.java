package gregochr.cinefilm.still;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
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

@WebMvcTest(StillController.class)
@Import(SecurityConfig.class)
class StillControllerTest {

    @TempDir
    Path dir;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private StillService service;

    @Test
    void servesTheJpegWithAPrivateDayLongCache() throws Exception {
        Path jpeg = Files.write(dir.resolve("5-12-10-1.jpg"), new byte[] {(byte) 0xFF, (byte) 0xD8, 9});
        when(service.still(5L, 12_345L)).thenReturn(jpeg);

        var result = mvc.perform(get("/api/editions/{id}/still", 5).param("atMs", "12345").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=86400, private"))
            .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).containsExactly(0xFF - 256, 0xD8 - 256, 9);
    }

    @Test
    void aMissingTimeIsPassedOnAsNullSoTheServiceAnswers400() throws Exception {
        when(service.still(5L, null)).thenThrow(new BadRequestException("atMs is required"));

        mvc.perform(get("/api/editions/{id}/still", 5).with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("atMs is required"));
    }

    @Test
    void aNonNumericTimeIs400() throws Exception {
        mvc.perform(get("/api/editions/{id}/still", 5).param("atMs", "soon").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void errorsKeepTheirStatusWhenOnlyImageJpegIsAcceptedNotA406() throws Exception {
        when(service.still(5L, 1L)).thenThrow(new BadRequestException("out of range"));
        when(service.still(6L, 1L)).thenThrow(new NotFoundException("No such edition"));
        when(service.still(7L, 1L)).thenThrow(new ServiceUnavailableException("busy"));

        for (var expected : new Object[][] {{5, 400, "out of range"}, {6, 404, "No such edition"}, {7, 503, "busy"}}) {
            mvc.perform(get("/api/editions/{id}/still", expected[0]).param("atMs", "1")
                    .header(HttpHeaders.ACCEPT, "image/jpeg").with(user("owner")))
                .andExpect(status().is((Integer) expected[1]))
                .andExpect(jsonPath("$.error").value((String) expected[2]));
        }
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/editions/{id}/still", 5).param("atMs", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
