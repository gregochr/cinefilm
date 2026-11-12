package gregochr.cinefilm.subtitle;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CueDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CuesDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SubtitleAdminController.class)
@Import(SecurityConfig.class)
class SubtitleAdminControllerTest {

    private static final SidecarDto DTO = new SidecarDto(5L, "Taken.en.forced.srt", "PRESENT", "matched", 7L,
        "Taken", 70L, "Original", 1, 1, false, true, 12, "No problems found", -250, true, false, 1700000000000L,
        true, null, null, null, "FILE", null, null, "Plays: nothing says it is wrong, but it is not proven yet");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SubtitleAdminService service;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.with(user("owner")).header("X-CineFilm", "1").contentType("application/json");
    }

    @Test
    void listReturnsEveryFileWithItsShape() throws Exception {
        when(service.list()).thenReturn(List.of(DTO));

        mvc.perform(get("/api/admin/subtitles").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(5))
            .andExpect(jsonPath("$[0].path").value("Taken.en.forced.srt"))
            .andExpect(jsonPath("$[0].presence").value("PRESENT"))
            .andExpect(jsonPath("$[0].state").value("matched"))
            .andExpect(jsonPath("$[0].filmId").value(7))
            .andExpect(jsonPath("$[0].filmTitle").value("Taken"))
            .andExpect(jsonPath("$[0].editionId").value(70))
            .andExpect(jsonPath("$[0].editionName").value("Original"))
            .andExpect(jsonPath("$[0].parts").value(1))
            .andExpect(jsonPath("$[0].part").value(1))
            .andExpect(jsonPath("$[0].whole").value(false))
            .andExpect(jsonPath("$[0].ownerAttached").value(true))
            .andExpect(jsonPath("$[0].cueCount").value(12))
            .andExpect(jsonPath("$[0].parseReport").value("No problems found"))
            .andExpect(jsonPath("$[0].offsetMs").value(-250))
            .andExpect(jsonPath("$[0].verified").value(true))
            .andExpect(jsonPath("$[0].endsAfterRuntime").value(false))
            .andExpect(jsonPath("$[0].version").value(1700000000000L))
            .andExpect(jsonPath("$[0].served").value(true));
    }

    @Test
    void cuesReturnsTheOffsetAndTheCues() throws Exception {
        when(service.cues(5L)).thenReturn(new CuesDto(1500, List.of(new CueDto(11_500, 13_500, "Hello"))));

        mvc.perform(get("/api/admin/subtitles/5/cues").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offsetMs").value(1500))
            .andExpect(jsonPath("$.cues[0].startMs").value(11_500))
            .andExpect(jsonPath("$.cues[0].endMs").value(13_500))
            .andExpect(jsonPath("$.cues[0].text").value("Hello"));
    }

    @Test
    void cuesOfAnUnknownFileIs404WithTheMessage() throws Exception {
        when(service.cues(9L)).thenThrow(new NotFoundException("No such subtitle"));

        mvc.perform(get("/api/admin/subtitles/9/cues").with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle"));
    }

    @Test
    void attachPassesTheEditionAndThePart() throws Exception {
        when(service.attach(5L, new AttachRequest(70L, 2))).thenReturn(DTO);
        when(service.attach(5L, new AttachRequest(70L, null))).thenReturn(DTO);

        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("{\"editionId\":70,\"part\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(5));
        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("{\"editionId\":70,\"part\":null}"))
            .andExpect(status().isOk());
        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("{\"editionId\":70}"))
            .andExpect(status().isOk());
        verify(service).attach(5L, new AttachRequest(70L, 2));
    }

    @Test
    void attachErrorsMapTo400And404WithTheirMessages() throws Exception {
        when(service.attach(5L, new AttachRequest(null, null))).thenThrow(new BadRequestException("Pick an edition"));
        when(service.attach(5L, new AttachRequest(9L, null))).thenThrow(new NotFoundException("No such edition"));

        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pick an edition"));
        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("{\"editionId\":9}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void aMalformedBodyOrIdIs400InvalidRequest() throws Exception {
        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(post("/api/admin/subtitles/5/attach")).content("{\"editionId\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(post("/api/admin/subtitles/abc/detach")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void detachVerifyAndUnverifyCallTheService() throws Exception {
        when(service.detach(5L)).thenReturn(DTO);
        when(service.verify(5L)).thenReturn(DTO);
        when(service.unverify(5L)).thenReturn(DTO);

        mvc.perform(signedIn(post("/api/admin/subtitles/5/detach"))).andExpect(status().isOk());
        mvc.perform(signedIn(post("/api/admin/subtitles/5/verify"))).andExpect(status().isOk());
        mvc.perform(signedIn(delete("/api/admin/subtitles/5/verify"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true));
        verify(service).detach(5L);
        verify(service).verify(5L);
        verify(service).unverify(5L);
    }

    @Test
    void offsetPassesTheValueAndErrorsComeBackAs400() throws Exception {
        when(service.offset(5L, new OffsetRequest(-1250))).thenReturn(DTO);
        when(service.offset(5L, new OffsetRequest(700_000)))
            .thenThrow(new BadRequestException("The offset must be within 10 minutes either way"));

        mvc.perform(signedIn(put("/api/admin/subtitles/5/offset")).content("{\"offsetMs\":-1250}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(5));
        mvc.perform(signedIn(put("/api/admin/subtitles/5/offset")).content("{\"offsetMs\":700000}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The offset must be within 10 minutes either way"));
        verify(service).offset(5L, new OffsetRequest(-1250));
    }

    @Test
    void removingADownloadIs204AndPassesTheRowIdAndErrorsComeBackWithTheirBodies() throws Exception {
        doThrow(new BadRequestException("Only a subtitle downloaded from OpenSubtitles can be removed"))
            .when(service).remove(5L);
        doThrow(new NotFoundException("No such subtitle")).when(service).remove(9L);

        mvc.perform(signedIn(delete("/api/admin/subtitles/7")))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        verify(service).remove(7L);
        mvc.perform(signedIn(delete("/api/admin/subtitles/5")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Only a subtitle downloaded from OpenSubtitles can be removed"));
        mvc.perform(signedIn(delete("/api/admin/subtitles/9")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle"));
    }

    @Test
    void aDownloadsOriginReleaseFileIdAndReasonAreInTheJson() throws Exception {
        SidecarDto download = new SidecarDto(8L, "opensubtitles:70:5011", "PRESENT", "matched", 7L, "Taken", 70L,
            "Original", 1, null, true, false, 12, "Downloaded from OpenSubtitles", 0, false, false,
            1700000000000L, true, null, null, null, "OPENSUBTITLES", "Taken.2008.BluRay", 5011L,
            "Plays: it agrees with OpenSubtitles");
        when(service.list()).thenReturn(List.of(download, DTO));

        mvc.perform(get("/api/admin/subtitles").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].origin").value("OPENSUBTITLES"))
            .andExpect(jsonPath("$[0].release").value("Taken.2008.BluRay"))
            .andExpect(jsonPath("$[0].openSubtitlesFileId").value(5011))
            .andExpect(jsonPath("$[0].servedReason").value("Plays: it agrees with OpenSubtitles"))
            .andExpect(jsonPath("$[1].origin").value("FILE"))
            .andExpect(jsonPath("$[1].release").value((Object) null))
            .andExpect(jsonPath("$[1].openSubtitlesFileId").value((Object) null))
            .andExpect(jsonPath("$[1].servedReason")
                .value("Plays: nothing says it is wrong, but it is not proven yet"));
    }

    @Test
    void everyRouteIs401SignedOutWithTheJsonBody() throws Exception {
        mvc.perform(get("/api/admin/subtitles")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(get("/api/admin/subtitles/5/cues")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        for (MockHttpServletRequestBuilder request : List.of(post("/api/admin/subtitles/5/attach"),
            post("/api/admin/subtitles/5/detach"), put("/api/admin/subtitles/5/offset"),
            post("/api/admin/subtitles/5/verify"), delete("/api/admin/subtitles/5/verify"),
            delete("/api/admin/subtitles/5"))) {
            mvc.perform(request.header("X-CineFilm", "1").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication required"));
        }
    }

    @Test
    void everyChangeWithoutTheCsrfHeaderIs403() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/api/admin/subtitles/5/attach"),
            post("/api/admin/subtitles/5/detach"), put("/api/admin/subtitles/5/offset"),
            post("/api/admin/subtitles/5/verify"), delete("/api/admin/subtitles/5/verify"),
            delete("/api/admin/subtitles/5"))) {
            mvc.perform(request.with(user("owner")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        }
    }

    @Test
    void aFileServedByItsEditionCarriesTheOpenSubtitlesCheckAndOtherFilesCarryNull() throws Exception {
        CheckDto check = new CheckDto("CHECK", List.of("OpenSubtitles has 41 lines, this file has 12",
            "OpenSubtitles' file is 1.2 s later"), 1200, 0.5, 12, 41, "Taken.2008.BluRay",
            Instant.parse("2024-04-01T01:30:00Z"));
        SidecarDto checked = new SidecarDto(5L, "Taken.en.forced.srt", "PRESENT", "matched", 7L, "Taken", 70L,
            "Original", 1, 1, false, true, 12, "No problems found", -250, true, false, 1700000000000L, true, check,
            null, null, "FILE", null, null, "Plays: nothing says it is wrong, but it is not proven yet");
        when(service.list()).thenReturn(List.of(checked, DTO));

        mvc.perform(get("/api/admin/subtitles").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].check.verdict").value("CHECK"))
            .andExpect(jsonPath("$[0].check.reasons[0]").value("OpenSubtitles has 41 lines, this file has 12"))
            .andExpect(jsonPath("$[0].check.reasons[1]").value("OpenSubtitles' file is 1.2 s later"))
            .andExpect(jsonPath("$[0].check.bestOffsetMs").value(1200))
            .andExpect(jsonPath("$[0].check.matchedShare").value(0.5))
            .andExpect(jsonPath("$[0].check.servedCount").value(12))
            .andExpect(jsonPath("$[0].check.referenceCount").value(41))
            .andExpect(jsonPath("$[0].check.referenceRelease").value("Taken.2008.BluRay"))
            .andExpect(jsonPath("$[0].check.checkedAt").value("2024-04-01T01:30:00Z"))
            .andExpect(jsonPath("$[1].check").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void aFileCarriesWhoSetItsOffsetAndItsTimingFitAndOtherFilesCarryNull() throws Exception {
        SubtitleDtos.AlignmentDto alignment = new SubtitleDtos.AlignmentDto("ALIGNED", List.of(), 900, 0.92, 11, 12,
            true, Instant.parse("2024-04-01T02:30:00Z"));
        SidecarDto fitted = new SidecarDto(5L, "Taken.en.forced.srt", "PRESENT", "matched", 7L, "Taken", 70L,
            "Original", 1, 1, false, true, 12, "No problems found", 900, false, false, 1700000000000L, true, null,
            "AUTO", alignment, "FILE", null, null, "Plays: nothing says it is wrong, but it is not proven yet");
        SubtitleDtos.AlignmentDto doubtful = new SubtitleDtos.AlignmentDto("CHECK",
            List.of("Only 4 of 12 lines line up with the film's English track"), 600, 0.33, 4, 12, false,
            Instant.parse("2024-04-01T02:30:00Z"));
        SidecarDto checked = new SidecarDto(6L, "Alien.en.forced.srt", "PRESENT", "matched", 8L, "Alien", 80L,
            "Original", 1, 1, false, true, 12, "No problems found", 0, false, false, 1700000000000L, true, null,
            null, doubtful, "FILE", null, null, "Plays: nothing says it is wrong, but it is not proven yet");
        when(service.list()).thenReturn(List.of(fitted, checked, DTO));

        mvc.perform(get("/api/admin/subtitles").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].offsetSource").value("AUTO"))
            .andExpect(jsonPath("$[0].alignment.verdict").value("ALIGNED"))
            .andExpect(jsonPath("$[0].alignment.reasons").isEmpty())
            .andExpect(jsonPath("$[0].alignment.fittedOffsetMs").value(900))
            .andExpect(jsonPath("$[0].alignment.matchedShare").value(0.92))
            .andExpect(jsonPath("$[0].alignment.matchedCount").value(11))
            .andExpect(jsonPath("$[0].alignment.servedCount").value(12))
            .andExpect(jsonPath("$[0].alignment.applied").value(true))
            .andExpect(jsonPath("$[0].alignment.checkedAt").value("2024-04-01T02:30:00Z"))
            .andExpect(jsonPath("$[1].offsetSource").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[1].alignment.verdict").value("CHECK"))
            .andExpect(jsonPath("$[1].alignment.reasons[0]")
                .value("Only 4 of 12 lines line up with the film's English track"))
            .andExpect(jsonPath("$[1].alignment.applied").value(false))
            .andExpect(jsonPath("$[2].offsetSource").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$[2].alignment").value(org.hamcrest.Matchers.nullValue()));
    }
}
