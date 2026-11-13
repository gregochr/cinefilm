package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Full English subtitles end to end: the film detail, the WebVTT route and the Admin rows, as JSON over MockMvc. */
class FullSubtitleApiTest extends SubtitleTestBase {

    private static final String HELLO_WORLD = "WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
        + "00:00:20.000 --> 00:00:22.500\nWorld\n";

    @Test
    void theFilmDetailOffersTheFullSubtitleAndTheForcedOneIsStillNull() throws Exception {
        Film taken = filmWithParts("Taken", 1, 90);
        fullSidecar("Taken.en.srt", fileId(taken, 1), false).setUpdatedAt(NOW.plusMillis(1234));

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].fullSubtitle.source").value("SIDECAR"))
            .andExpect(jsonPath("$.editions[0].fullSubtitle.version").isNumber())
            .andExpect(jsonPath("$.editions[0].forcedSubtitle").value((Object) null));
    }

    @Test
    void theFilmDetailHasNullWhenTheEditionHasNoFullSubtitle() throws Exception {
        Film heat = filmWithParts("Heat", 1, 90);
        sidecar("Heat.en.forced.srt", fileId(heat, 1), false);

        mvc.perform(get("/api/films/{id}", heat.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].fullSubtitle").value((Object) null))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.source").value("SIDECAR"));
    }

    @Test
    void theVttRouteServesTheFileAndIgnoresTheVersionParameter() throws Exception {
        Film taken = filmWithParts("Taken", 1, 90);
        fullSidecar("Taken.en.srt", fileId(taken, 1), false);
        Long edition = taken.getEditions().get(0).getId();

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", edition).param("v", "99"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/vtt;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
            .andExpect(content().string(HELLO_WORLD));
        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition))
            .andExpect(status().isNotFound());
    }

    @Test
    void theEnglishFileBesideAnEnglishLanguageFilmServesToo() throws Exception {
        Film heat = filmWithParts("Heat", 1, 90);
        heat.setOriginalLanguage("en");
        films.saveAndFlush(heat);
        fullSidecar("Heat.en.srt", fileId(heat, 1), false);

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", heat.getEditions().get(0).getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(HELLO_WORLD));
    }

    @Test
    void aSplitEditionsPartFilesAreStitchedOnTheEditionTimelineWithTheirOffsets() throws Exception {
        Film towers = filmWithParts("The Two Towers", 2, 60);
        fullSidecar("The Two Towers - pt1.en.srt", fileId(towers, 1), false);
        fullSidecar("The Two Towers - pt2.eng.srt", fileId(towers, 2), false).setOffsetMs(-3000);

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", towers.getEditions().get(0).getId()))
            .andExpect(status().isOk())
            .andExpect(content().string("WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
                + "00:00:20.000 --> 00:00:22.500\nWorld\n\n01:00:07.000 --> 01:00:09.000\nHello\n\n"
                + "01:00:17.000 --> 01:00:19.500\nWorld\n"));
    }

    @Test
    void twoFilesForOnePlaceAreAConflictAndNeitherPlays() throws Exception {
        Film taken = filmWithParts("Taken", 1, 90);
        fullSidecar("Taken.en.srt", fileId(taken, 1), false);
        fullSidecar("Taken.eng.srt", fileId(taken, 1), false);
        Long edition = taken.getEditions().get(0).getId();

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(jsonPath("$.editions[0].fullSubtitle").value((Object) null));
        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", edition))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No full subtitles"));
        mvc.perform(get("/api/admin/subtitles"))
            .andExpect(jsonPath("$[0].state").value("conflict"))
            .andExpect(jsonPath("$[1].state").value("conflict"));
    }

    @Test
    void theVttRouteIs404WhenThereIsNoneOrTheEditionIsUnknown() throws Exception {
        Film heat = filmWithParts("Heat", 1, 90);

        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", heat.getEditions().get(0).getId()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No full subtitles"));
        mvc.perform(get("/api/editions/9999/full-subtitles.vtt"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void theOwnerAttachesAndNudgesAFullFileInAdminAndItCannotBeCheckedAsForced() throws Exception {
        Film towers = filmWithParts("The Two Towers", 2, 60);
        SubtitleSidecar row = fullSidecar("LOTR.en.srt", null, false);
        Long edition = towers.getEditions().get(0).getId();

        mvc.perform(get("/api/admin/subtitles"))
            .andExpect(jsonPath("$[0].path").value("LOTR.en.srt"))
            .andExpect(jsonPath("$[0].kind").value("FULL"))
            .andExpect(jsonPath("$[0].state").value("unmatched"));
        mvc.perform(post("/api/admin/subtitles/{id}/attach", row.getId())
                .content("{\"editionId\":" + edition + ",\"part\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("FULL"))
            .andExpect(jsonPath("$.state").value("matched"))
            .andExpect(jsonPath("$.whole").value(true))
            .andExpect(jsonPath("$.served").value(true))
            .andExpect(jsonPath("$.servedReason").value("Ready to play as English (full)"));
        mvc.perform(put("/api/admin/subtitles/{id}/offset", row.getId()).content("{\"offsetMs\":-500}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offsetMs").value(-500));
        mvc.perform(get("/api/editions/{id}/full-subtitles.vtt", edition))
            .andExpect(content().string("WEBVTT\n\n00:00:09.500 --> 00:00:11.500\nHello\n\n"
                + "00:00:19.500 --> 00:00:22.000\nWorld\n"));
        mvc.perform(get("/api/films/{id}", towers.getId()))
            .andExpect(jsonPath("$.editions[0].fullSubtitle.source").value("SIDECAR"))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle").value((Object) null));
        mvc.perform(post("/api/admin/subtitles/{id}/verify", row.getId()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Only a forced subtitle can be checked"));
        mvc.perform(post("/api/admin/subtitles/{id}/detach", row.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ownerAttached").value(false))
            .andExpect(jsonPath("$.state").value("unmatched"));
    }
}
