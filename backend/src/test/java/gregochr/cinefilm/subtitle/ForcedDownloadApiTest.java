package gregochr.cinefilm.subtitle;

import static gregochr.cinefilm.TestData.NOW;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Film;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Downloaded forced subtitles end to end: the film detail, the WebVTT route and the Admin routes, as JSON. */
class ForcedDownloadApiTest extends ForcedDownloadTestBase {

    private static final String HELLO_WORLD = "WEBVTT\n\n00:00:10.000 --> 00:00:12.000\nHello\n\n"
        + "00:00:20.000 --> 00:00:22.500\nWorld\n";

    private Film film() {
        return searchedFilm("Taken", 1, result(5011, "Taken.2008.BluRay"), result(5012, "Taken.2008.WEB"));
    }

    @Test
    void theFilmDetailOffersADownloadAsTheForcedSubtitleWithItsVersion() throws Exception {
        Film taken = film();
        download(taken, 5011).setUpdatedAt(NOW.plusMillis(4321));

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.source").value("DOWNLOAD"))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.ownerVerified").value(false))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.version").isNumber());
    }

    @Test
    void theVttRouteServesTheDownloadsCues() throws Exception {
        Film taken = film();
        download(taken, 5011);

        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition(taken).getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(HELLO_WORLD));
    }

    @Test
    void aFailedOwnerFileIsReplacedInTheDetailAndTheRouteByTheDownload() throws Exception {
        Film taken = film();
        sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        fileCheck(taken, "CHECK", reference(taken, 5011));
        SubtitleSidecar download = download(taken, 5012);
        download.setCuesText("1\n00:01:00,000 --> 00:01:02,000\nOther\n\n");
        sidecars.saveAndFlush(download);

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle.source").value("DOWNLOAD"));
        mvc.perform(get("/api/editions/{id}/forced-subtitles.vtt", edition(taken).getId()))
            .andExpect(content().string("WEBVTT\n\n00:01:00.000 --> 00:01:02.000\nOther\n"));
    }

    @Test
    void theAdminListCarriesTheDownloadsFieldsAndTheOwnersFileCarriesNullsAndEveryReason() throws Exception {
        Film taken = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);
        fileCheck(taken, "CHECK", reference(taken, 5011));
        SubtitleSidecar download = download(taken, 5012);

        mvc.perform(get("/api/admin/subtitles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id==" + download.getId() + ")].origin").value("OPENSUBTITLES"))
            .andExpect(jsonPath("$[?(@.id==" + download.getId() + ")].release").value("Release.5012"))
            .andExpect(jsonPath("$[?(@.id==" + download.getId() + ")].openSubtitlesFileId").value(5012))
            .andExpect(jsonPath("$[?(@.id==" + download.getId() + ")].served").value(true))
            .andExpect(jsonPath("$[?(@.id==" + download.getId() + ")].servedReason")
                .value("Plays instead of the owner's file, which failed its checks; not checked yet"))
            .andExpect(jsonPath("$[?(@.id==" + owner.getId() + ")].origin").value("FILE"))
            .andExpect(jsonPath("$[?(@.id==" + owner.getId() + ")].served").value(false))
            .andExpect(jsonPath("$[?(@.id==" + owner.getId() + ")].check.verdict").value("CHECK"))
            .andExpect(jsonPath("$[?(@.id==" + owner.getId() + ")].servedReason").value(
                "Replaced by an OpenSubtitles download that has not been checked yet; this file failed its checks"));
    }

    @Test
    void theOwnerRemovesADownloadAndTheEditionHasNoForcedSubtitleAgain() throws Exception {
        Film taken = film();
        SubtitleSidecar download = download(taken, 5011);

        mvc.perform(authed(MockMvcRequestBuilders.delete("/api/admin/subtitles/{id}", download.getId())))
            .andExpect(status().isNoContent());
        sidecars.flush();

        mvc.perform(get("/api/films/{id}", taken.getId()))
            .andExpect(jsonPath("$.editions[0].forcedSubtitle").value((Object) null));
        mvc.perform(authed(MockMvcRequestBuilders.delete("/api/admin/subtitles/{id}", download.getId())))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such subtitle"));
    }

    @Test
    void anOwnersFileCannotBeRemovedThroughTheRoute() throws Exception {
        Film taken = film();
        SubtitleSidecar owner = sidecar("Taken.en.forced.srt", fileId(taken, 1), false);

        mvc.perform(authed(MockMvcRequestBuilders.delete("/api/admin/subtitles/{id}", owner.getId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Only a subtitle downloaded from OpenSubtitles can be removed"));
    }

    @Test
    void theNeedListSaysAFilmWithOnlyADownloadHasAForcedSubtitle() throws Exception {
        Film taken = film();
        download(taken, 5011);

        mvc.perform(get("/api/admin/forced-subtitles/films"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.films[0].need").value("NEEDED"))
            .andExpect(jsonPath("$.films[0].reason").value("OPENSUBTITLES"))
            .andExpect(jsonPath("$.films[0].hasForcedSubtitle").value(true))
            .andExpect(jsonPath("$.counts.neededMissing").value(0));
    }

    @Test
    void aFilmThatNeedsSubtitlesAndHasNoneIsCountedMissingUntilADownloadExists() throws Exception {
        film();

        mvc.perform(get("/api/admin/forced-subtitles/films"))
            .andExpect(jsonPath("$.films[0].hasForcedSubtitle").value(false))
            .andExpect(jsonPath("$.counts.neededMissing").value(1));
    }
}
