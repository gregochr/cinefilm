package gregochr.cinefilm.transcode;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionDtos.RenditionDto;
import gregochr.cinefilm.transcode.SessionDtos.SessionCreated;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HlsSessionController.class)
@Import(SecurityConfig.class)
class HlsSessionControllerTest {

    private static final String ID = "5b0f1c1e-6a52-4d5e-9a4c-0a7d3c1f2b11";

    @TempDir
    Path dir;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SessionService service;

    private static final String BODY =
        "{\"audioStreamId\":3,\"startMs\":120000,\"quality\":\"auto\",\"probeKbps\":5000}";

    @Test
    void createReturns201WithTheSessionTheClientNeeds() throws Exception {
        when(service.create(40L, new CreateRequest(3L, 120_000L, "auto", 5000L))).thenReturn(new SessionCreated(ID,
            "/api/sessions/" + ID + "/master.m3u8", "720p",
            List.of(new RenditionDto("original", 1080, 24_000_000, true), new RenditionDto("720p", 720, 2_500_000,
                false))));

        mvc.perform(post("/api/editions/40/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content(BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionId").value(ID))
            .andExpect(jsonPath("$.masterUrl").value("/api/sessions/" + ID + "/master.m3u8"))
            .andExpect(jsonPath("$.startRung").value("720p"))
            .andExpect(jsonPath("$.renditions[0].id").value("original"))
            .andExpect(jsonPath("$.renditions[0].height").value(1080))
            .andExpect(jsonPath("$.renditions[0].bitrate").value(24_000_000))
            .andExpect(jsonPath("$.renditions[0].copy").value(true))
            .andExpect(jsonPath("$.renditions[1].id").value("720p"))
            .andExpect(jsonPath("$.renditions[1].copy").value(false));
    }

    @Test
    void createOptionalFieldsMayBeOmitted() throws Exception {
        when(service.create(40L, new CreateRequest(null, null, "original", null))).thenReturn(new SessionCreated(ID,
            "/api/sessions/" + ID + "/master.m3u8", "original", List.of()));

        mvc.perform(post("/api/editions/40/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"quality\":\"original\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.startRung").value("original"));
    }

    @Test
    void createMapsTheServiceErrorsToTheirStatusesWithTheExactMessage() throws Exception {
        when(service.create(1L, new CreateRequest(null, null, "bogus", null)))
            .thenThrow(new BadRequestException("Invalid quality"));
        when(service.create(2L, new CreateRequest(null, null, "auto", null)))
            .thenThrow(new ConflictException("Transcoding is unavailable"));
        when(service.create(3L, new CreateRequest(null, null, "auto", null)))
            .thenThrow(new NotFoundException("No such edition"));

        mvc.perform(post("/api/editions/1/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"quality\":\"bogus\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Invalid quality"));
        mvc.perform(post("/api/editions/2/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"quality\":\"auto\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Transcoding is unavailable"));
        mvc.perform(post("/api/editions/3/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("{\"quality\":\"auto\"}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void createWithAMalformedBodyOrIdIs400() throws Exception {
        mvc.perform(post("/api/editions/40/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content("not json"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(post("/api/editions/abc/sessions").with(user("owner")).header("X-CineFilm", "1")
                .contentType("application/json").content(BODY))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Invalid request"));

        verifyNoInteractions(service);
    }

    @Test
    void createNeedsTheSignInAndTheCsrfHeader() throws Exception {
        mvc.perform(post("/api/editions/40/sessions").header("X-CineFilm", "1").contentType("application/json")
                .content(BODY))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/editions/40/sessions").with(user("owner")).contentType("application/json")
                .content(BODY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verifyNoInteractions(service);
    }

    @Test
    void theMasterPlaylistIsServedAsHlsAndNeverCached() throws Exception {
        when(service.master(ID)).thenReturn("#EXTM3U\n");

        mvc.perform(get("/api/sessions/" + ID + "/master.m3u8").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.apple.mpegurl"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string("#EXTM3U\n"));
    }

    @Test
    void theMediaPlaylistIsServedAsHlsAndNeverCached() throws Exception {
        when(service.media(ID, "720p")).thenReturn("#EXTM3U\n#EXT-X-ENDLIST\n");

        mvc.perform(get("/api/sessions/" + ID + "/720p/index.m3u8").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/vnd.apple.mpegurl"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string("#EXTM3U\n#EXT-X-ENDLIST\n"));
    }

    @Test
    void aSegmentIsServedWithItsLengthAsMpegTsAndNeverCached() throws Exception {
        Path segment = Files.write(dir.resolve("seg-3.ts"), new byte[] {0x47, 1, 2, 3, 4});
        when(service.segment(ID, "original", "seg-3.ts")).thenReturn(segment);

        mvc.perform(get("/api/sessions/" + ID + "/original/seg-3.ts").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "video/mp2t"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().longValue("Content-Length", 5))
            .andExpect(content().bytes(new byte[] {0x47, 1, 2, 3, 4}));
    }

    @Test
    void aSegmentNotReadyIs503WithRetryAfterOneAndNeverA404() throws Exception {
        when(service.segment(ID, "original", "seg-3.ts")).thenThrow(new SegmentNotReadyException());

        mvc.perform(get("/api/sessions/" + ID + "/original/seg-3.ts").with(user("owner")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "1"))
            .andExpect(jsonPath("$.error").value("The segment is not ready yet"));
    }

    @Test
    void aFailedSessionIs503WithoutRetryAfter() throws Exception {
        when(service.segment(ID, "original", "seg-3.ts")).thenThrow(new SessionUnavailableException());

        mvc.perform(get("/api/sessions/" + ID + "/original/seg-3.ts").with(user("owner")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().doesNotExist("Retry-After"))
            .andExpect(jsonPath("$.error").value("Transcoding failed for this session"));
    }

    @Test
    void anUnknownSessionOrSegmentIs404() throws Exception {
        when(service.master(ID)).thenThrow(new NotFoundException("No such session"));
        when(service.media(ID, "720p")).thenThrow(new NotFoundException("No such session"));
        when(service.segment(ID, "720p", "seg-1000000.ts")).thenThrow(new NotFoundException("No such segment"));

        mvc.perform(get("/api/sessions/" + ID + "/master.m3u8").with(user("owner")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such session"));
        mvc.perform(get("/api/sessions/" + ID + "/720p/index.m3u8").with(user("owner")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such session"));
        mvc.perform(get("/api/sessions/" + ID + "/720p/seg-1000000.ts").with(user("owner")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such segment"));
    }

    @Test
    void everyReadRouteNeedsTheSignIn() throws Exception {
        for (String url : List.of("/api/sessions/" + ID + "/master.m3u8", "/api/sessions/" + ID + "/720p/index.m3u8",
            "/api/sessions/" + ID + "/720p/seg-1.ts")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));
        }

        verifyNoInteractions(service);
    }

    @Test
    void deleteEndsTheSessionAndAnswers204() throws Exception {
        mvc.perform(delete("/api/sessions/" + ID).with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNoContent());

        verify(service).end(ID);
    }

    @Test
    void deleteNeedsTheSignInAndTheCsrfHeader() throws Exception {
        mvc.perform(delete("/api/sessions/" + ID).header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(delete("/api/sessions/" + ID).with(user("owner")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verify(service, never()).end(ID);
    }

    @Test
    void deleteOfAMalformedIdIs404() throws Exception {
        doThrow(new NotFoundException("No such session")).when(service).end("x");

        mvc.perform(delete("/api/sessions/x").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such session"));
    }
}
