package gregochr.cinefilm.subtitle;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.config.UnprocessableException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadDto;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadRequest;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.ResultDto;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.SearchDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(EditionOpenSubtitlesController.class)
@Import(SecurityConfig.class)
class EditionOpenSubtitlesControllerTest {

    private static final String SEARCH = "/api/admin/editions/40/opensubtitles";
    private static final String DOWNLOAD = "/api/admin/editions/40/opensubtitles/download";
    private static final String BODY = "{\"fileId\":5011,\"kind\":\"forced\"}";
    private static final DownloadRequest REQUEST = new DownloadRequest(5011L, "forced");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EditionOpenSubtitlesService service;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
        return builder.with(user("owner")).header("X-CineFilm", "1").contentType("application/json");
    }

    @Test
    void theSearchReturnsTheRankedResultsWithEveryField() throws Exception {
        when(service.search(40L, "forced")).thenReturn(new SearchDto(40L, "forced", "tt0936501", 2, List.of(
            new ResultDto(5012, "Taken.Extended", "Taken.Extended.srt", 23.976, 120, true, false,
                "2020-01-02T03:04:05Z"),
            new ResultDto(5011, "Taken", null, null, 0, false, true, null))));

        mvc.perform(get(SEARCH).param("kind", "forced").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editionId").value(40))
            .andExpect(jsonPath("$.kind").value("forced"))
            .andExpect(jsonPath("$.imdbId").value("tt0936501"))
            .andExpect(jsonPath("$.downloadsLeft").value(2))
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(jsonPath("$.results[0].fileId").value(5012))
            .andExpect(jsonPath("$.results[0].release").value("Taken.Extended"))
            .andExpect(jsonPath("$.results[0].fileName").value("Taken.Extended.srt"))
            .andExpect(jsonPath("$.results[0].fps").value(23.976))
            .andExpect(jsonPath("$.results[0].downloadCount").value(120))
            .andExpect(jsonPath("$.results[0].fromTrusted").value(true))
            .andExpect(jsonPath("$.results[0].hearingImpaired").value(false))
            .andExpect(jsonPath("$.results[0].uploadDate").value("2020-01-02T03:04:05Z"))
            .andExpect(jsonPath("$.results[1].fileId").value(5011))
            .andExpect(jsonPath("$.results[1].fileName").value((Object) null))
            .andExpect(jsonPath("$.results[1].fps").value((Object) null))
            .andExpect(jsonPath("$.results[1].hearingImpaired").value(true));
    }

    @Test
    void theSearchNeedsNoCsrfHeader() throws Exception {
        when(service.search(40L, "full")).thenReturn(new SearchDto(40L, "full", "tt0936501", 3, List.of()));

        mvc.perform(get(SEARCH).param("kind", "full").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("full"))
            .andExpect(jsonPath("$.results.length()").value(0));
    }

    @Test
    void theSearchPassesAMissingKindOnAsNullAndTheBadKindIs400() throws Exception {
        when(service.search(40L, null)).thenThrow(new BadRequestException("The kind must be full or forced"));

        mvc.perform(get(SEARCH).with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The kind must be full or forced"));
        verify(service).search(40L, null);
    }

    @Test
    void anInvalidKindIs400() throws Exception {
        when(service.search(40L, "both")).thenThrow(new BadRequestException("The kind must be full or forced"));

        mvc.perform(get(SEARCH).param("kind", "both").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The kind must be full or forced"));
    }

    @Test
    void aFilmWithNoImdbIdIs400() throws Exception {
        when(service.search(40L, "forced")).thenThrow(new BadRequestException(
            "This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first."));

        mvc.perform(get(SEARCH).param("kind", "forced").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                "This film has no IMDb id, so OpenSubtitles cannot be searched. Fix its match first."));
    }

    @Test
    void anUnknownEditionIs404OnBothRoutes() throws Exception {
        when(service.search(40L, "forced")).thenThrow(new NotFoundException("No such edition"));
        when(service.download(40L, REQUEST)).thenThrow(new NotFoundException("No such edition"));

        mvc.perform(get(SEARCH).param("kind", "forced").with(user("owner")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void unconfiguredIs503OnBothRoutes() throws Exception {
        when(service.search(40L, "forced")).thenThrow(new ServiceUnavailableException("OpenSubtitles is not set up"));
        when(service.download(40L, REQUEST))
            .thenThrow(new ServiceUnavailableException("OpenSubtitles downloads are not set up"));

        mvc.perform(get(SEARCH).param("kind", "forced").with(user("owner")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("OpenSubtitles is not set up"));
        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("OpenSubtitles downloads are not set up"));
    }

    @Test
    void anOutageIs503WithItsOwnTextOnBothRoutes() throws Exception {
        when(service.search(40L, "forced")).thenThrow(new OpenSubtitlesUnavailableException("secret url"));
        when(service.download(40L, REQUEST)).thenThrow(new OpenSubtitlesUnavailableException("secret url"));

        mvc.perform(get(SEARCH).param("kind", "forced").with(user("owner")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("OpenSubtitles is not answering. Try again in a few minutes."));
        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("OpenSubtitles is not answering. Try again in a few minutes."));
    }

    @Test
    void theDownloadPassesTheRequestOnAndAnswersWhatWasDownloaded() throws Exception {
        when(service.download(40L, REQUEST)).thenReturn(new DownloadDto(40L, "forced", 5011, "Taken.Extended", 2));

        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editionId").value(40))
            .andExpect(jsonPath("$.kind").value("forced"))
            .andExpect(jsonPath("$.fileId").value(5011))
            .andExpect(jsonPath("$.release").value("Taken.Extended"))
            .andExpect(jsonPath("$.downloadsLeft").value(2));
        verify(service).download(40L, REQUEST);
    }

    @Test
    void aFileThatWasNotInARecentSearchOfThatEditionAndKindIs400() throws Exception {
        when(service.download(40L, REQUEST)).thenThrow(new BadRequestException(
            "That file was not in a recent search for this edition. Search again."));
        when(service.download(41L, new DownloadRequest(5011L, "full"))).thenThrow(new BadRequestException(
            "That file was not in a recent search for this edition. Search again."));

        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                "That file was not in a recent search for this edition. Search again."));
        mvc.perform(signedIn(post("/api/admin/editions/41/opensubtitles/download"))
                .content("{\"fileId\":5011,\"kind\":\"full\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                "That file was not in a recent search for this edition. Search again."));
    }

    @Test
    void anExpiredFileIdIs400WithTheSameText() throws Exception {
        when(service.download(40L, new DownloadRequest(5012L, "full"))).thenThrow(new BadRequestException(
            "That file was not in a recent search for this edition. Search again."));

        mvc.perform(signedIn(post(DOWNLOAD)).content("{\"fileId\":5012,\"kind\":\"full\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(
                "That file was not in a recent search for this edition. Search again."));
    }

    @Test
    void aBadKindOrMissingFileIdInTheBodyIs400() throws Exception {
        when(service.download(40L, new DownloadRequest(5011L, "both")))
            .thenThrow(new BadRequestException("The kind must be full or forced"));
        when(service.download(40L, new DownloadRequest(null, "forced")))
            .thenThrow(new BadRequestException("A file id is required"));

        mvc.perform(signedIn(post(DOWNLOAD)).content("{\"fileId\":5011,\"kind\":\"both\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The kind must be full or forced"));
        mvc.perform(signedIn(post(DOWNLOAD)).content("{\"kind\":\"forced\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("A file id is required"));
    }

    @Test
    void aScanRunningIs409() throws Exception {
        when(service.download(40L, REQUEST)).thenThrow(new ConflictException("A scan is running"));

        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is running"));
    }

    @Test
    void anEditionWithNoOriginalFileIs409OnBothRoutes() throws Exception {
        when(service.search(40L, "forced"))
            .thenThrow(new ConflictException("This edition has no original file to attach subtitles to."));
        when(service.download(40L, REQUEST))
            .thenThrow(new ConflictException("This edition has no original file to attach subtitles to."));

        mvc.perform(get(SEARCH).param("kind", "forced").with(user("owner")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This edition has no original file to attach subtitles to."));
        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This edition has no original file to attach subtitles to."));
    }

    @Test
    void theDailyLimitIs429WithTheExactText() throws Exception {
        when(service.download(40L, REQUEST)).thenThrow(new OpenSubtitlesQuotaException(Instant.parse(
            "2024-04-01T00:00:00Z")));

        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("OpenSubtitles daily limit reached — try again tomorrow."));
    }

    @Test
    void aChargedDownloadThatMayBeTriedAgainIs502() throws Exception {
        when(service.download(40L, REQUEST)).thenThrow(new OpenSubtitlesChargedException("timeout", false));

        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("OpenSubtitles counted this download against today's limit "
                + "but could not deliver the file. Try again."));
    }

    @Test
    void aFileThatWasFetchedButIsNotUsableIs422WithTheServicesText() throws Exception {
        when(service.download(40L, REQUEST))
            .thenThrow(new UnprocessableException("That subtitle file could not be read."));
        when(service.download(40L, new DownloadRequest(6011L, "full")))
            .thenThrow(new UnprocessableException("That file is too short to be a full subtitle."));

        mvc.perform(signedIn(post(DOWNLOAD)).content(BODY))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error").value("That subtitle file could not be read."));
        mvc.perform(signedIn(post(DOWNLOAD)).content("{\"fileId\":6011,\"kind\":\"full\"}"))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error").value("That file is too short to be a full subtitle."));
    }

    @Test
    void aMalformedBodyOrIdIs400InvalidRequest() throws Exception {
        mvc.perform(signedIn(post(DOWNLOAD)).content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(post(DOWNLOAD)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(signedIn(post("/api/admin/editions/abc/opensubtitles/download")).content(BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(get("/api/admin/editions/abc/opensubtitles").param("kind", "full").with(user("owner")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void bothRoutesAre401SignedOutWithTheJsonBody() throws Exception {
        mvc.perform(get(SEARCH).param("kind", "forced")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post(DOWNLOAD).header("X-CineFilm", "1").contentType("application/json").content(BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void theDownloadWithoutTheCsrfHeaderIs403() throws Exception {
        mvc.perform(post(DOWNLOAD).with(user("owner")).contentType("application/json").content(BODY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }
}
