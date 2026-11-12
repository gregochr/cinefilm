package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Forced subtitles end to end: the film detail, the WebVTT route and the Admin routes, as JSON over MockMvc. */
class ForcedSubtitleApiTest extends SubtitleTestBase {

    private static final String HELLO_WORLD = "WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
        + "00:00:20.000 --> 00:00:22.500\nWorld\n";

    @Test
    void theFilmDetailOffersTheForcedSubtitleOfAnEditionThatHasOne() throws Exception {
        Film taken = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(taken, 1), false).setUpdatedAt(NOW.plusMillis(1234));

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.source").value("SIDECAR"))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.ownerVerified").value(false))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.version").isNumber());
    }

    @Test
    void theFilmDetailHasNullWhenTheEditionHasNoForcedSubtitle() throws Exception {
        Film heat = filmWithParts("Heat", 1, 90);

        mvc.perform(get("/api/films/{id}", heat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].forcedSubtitle").value((Object) null));
    }

    @Test
    void theVttRouteServesTheSubtitleAndIgnoresTheVersionParameter() throws Exception {
        Film taken = filmWithParts("Taken", 1, 90);
        sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        Long edition = taken.getEditions().get(0).getId();

        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition).param("v", "99"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/vtt;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
            .andExpect(content().string(HELLO_WORLD));
        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition))
            .andExpect(content().string(HELLO_WORLD));
    }

    @Test
    void theVttRouteIs404WhenThereIsNoneOrTheEditionIsUnknown() throws Exception {
        Film heat = filmWithParts("Heat", 1, 90);

        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", heat.getEditions().get(0).getId()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No forced subtitles"));
        mvc.perform(get("/api/editions/9999/forced-subtitles.vtt"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void theOwnerAttachesNudgesAndChecksAnUnmatchedFileAndTheDetailFollows() throws Exception {
        Film towers = filmWithParts("The Two Towers", 2, 60);
        SubtitleSidecar row = sidecar("LOTR.en.forced.srt", null, false);
        Long edition = towers.getEditions().get(0).getId();

        mvc.perform(get("/api/admin/subtitles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].path").value("LOTR.en.forced.srt"))
            .andExpect(jsonPath("$[0].state").value("unmatched"))
            .andExpect(jsonPath("$[0].filmId").value((Object) null));
        mvc.perform(post("/api/admin/subtitles/{id}/attach", row.getId())
                .content("{\"editionId\":" + edition + ",\"part\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("matched"))
            .andExpect(jsonPath("$.filmTitle").value("The Two Towers"))
            .andExpect(jsonPath("$.whole").value(true))
            .andExpect(jsonPath("$.ownerAttached").value(true));
        mvc.perform(put("/api/admin/subtitles/{id}/offset", row.getId()).content("{\"offsetMs\":-500}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offsetMs").value(-500));
        mvc.perform(post("/api/admin/subtitles/{id}/verify", row.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true));
        mvc.perform(get("/api/films/{id}", towers.getId()))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.ownerVerified").value(true));
        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition))
            .andExpect(content().string("WEBVTT\n\n00:00:09.500 --> 00:00:11.500\nHello\n\n"
                + "00:00:19.500 --> 00:00:22.000\nWorld\n"));
        mvc.perform(get("/api/admin/subtitles/{id}/cues", row.getId()))
            .andExpect(jsonPath("$.offsetMs").value(-500))
            .andExpect(jsonPath("$.cues[0].startMs").value(9500))
            .andExpect(jsonPath("$.cues[1].text").value("World"));
        mvc.perform(authed(MockMvcRequestBuilders.delete("/api/admin/subtitles/{id}/verify", row.getId())))
            .andExpect(jsonPath("$.verified").value(false));
        mvc.perform(post("/api/admin/subtitles/{id}/detach", row.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("unmatched"))
            .andExpect(jsonPath("$.ownerAttached").value(false));
    }

    @Test
    void adminValidationErrorsAre400And404WithTheirMessages() throws Exception {
        SubtitleSidecar row = sidecar("LOTR.en.forced.srt", null, false);

        mvc.perform(post("/api/admin/subtitles/{id}/attach", row.getId()).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Pick an edition"));
        mvc.perform(post("/api/admin/subtitles/{id}/attach", row.getId()).content("{\"editionId\":9999}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
        mvc.perform(put("/api/admin/subtitles/{id}/offset", row.getId()).content("{\"offsetMs\":600001}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The offset must be within 10 minutes either way"));
        mvc.perform(post("/api/admin/subtitles/{id}/verify", row.getId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Attach the subtitle to a film first"));
        mvc.perform(get("/api/admin/subtitles/9999/cues"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle"));
        mvc.perform(post("/api/admin/subtitles/9999/detach"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle"));
    }
}
