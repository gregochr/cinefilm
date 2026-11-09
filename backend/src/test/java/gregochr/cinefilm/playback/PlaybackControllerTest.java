package gregochr.cinefilm.playback;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.film.FilmDtos.Preferences;
import gregochr.cinefilm.playback.PlaybackDtos.HiddenResponse;
import gregochr.cinefilm.playback.PlaybackDtos.PlaybackSnapshot;
import gregochr.cinefilm.playback.PlaybackDtos.PreferencesRequest;
import gregochr.cinefilm.playback.PlaybackDtos.ProgressResponse;
import gregochr.cinefilm.playback.PlaybackDtos.WatchedResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(PlaybackController.class)
@Import(SecurityConfig.class)
class PlaybackControllerTest {

    private static final String JSON = "application/json";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlaybackService playback;

    @MockitoBean
    private PreferencesService preferences;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(user("owner")).header("X-CineFilm", "1").contentType(JSON);
    }

    @Test
    void progressPassesTheEditionAndPositionAndReturnsTheServiceResponse() throws Exception {
        when(playback.reportProgress(5L, 123_000L)).thenReturn(new ProgressResponse(123_000L, false));

        mvc.perform(signedIn(put("/api/editions/{id}/progress", 5)).content("{\"positionMs\":123000}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.positionMs").value(123_000))
            .andExpect(jsonPath("$.watched").value(false));
    }

    @Test
    void aValidationFailureFromTheServiceIsA400WithItsMessage() throws Exception {
        when(playback.reportProgress(5L, -1L))
            .thenThrow(new BadRequestException("positionMs must be a non-negative number"));

        mvc.perform(signedIn(put("/api/editions/{id}/progress", 5)).content("{\"positionMs\":-1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("positionMs must be a non-negative number"));
    }

    @Test
    void watchedPassesTheEditionAndTheFlag() throws Exception {
        when(playback.setWatched(6L, true)).thenReturn(new WatchedResponse(true,
            new PlaybackSnapshot(90_000L, false, null, true, Instant.parse("2024-03-30T10:00:00Z"))));

        mvc.perform(signedIn(post("/api/editions/{id}/watched", 6)).content("{\"watched\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watched").value(true))
            .andExpect(jsonPath("$.previous.positionMs").value(90_000))
            .andExpect(jsonPath("$.previous.watched").value(false))
            .andExpect(jsonPath("$.previous.watchedAt").value(nullValue()))
            .andExpect(jsonPath("$.previous.hiddenFromContinueWatching").value(true))
            .andExpect(jsonPath("$.previous.hiddenAt").value("2024-03-30T10:00:00Z"));
    }

    @Test
    void continueWatchingPassesTheEditionAndTheFlag() throws Exception {
        when(playback.setHiddenFromContinueWatching(6L, true)).thenReturn(new HiddenResponse(true));

        mvc.perform(signedIn(put("/api/editions/{id}/continue-watching", 6)).content("{\"hidden\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(true));
    }

    @Test
    void aMissingHiddenFlagIsA400WithTheServicesMessage() throws Exception {
        when(playback.setHiddenFromContinueWatching(6L, null))
            .thenThrow(new BadRequestException("hidden must be true or false"));

        mvc.perform(signedIn(put("/api/editions/{id}/continue-watching", 6)).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("hidden must be true or false"));
    }

    @Test
    void restorePassesTheWholeSnapshotAndReturnsWhatWasStored() throws Exception {
        PlaybackSnapshot snapshot = new PlaybackSnapshot(90_000L, true, Instant.parse("2024-03-01T10:00:00Z"),
            false, null);
        when(playback.restore(6L, snapshot)).thenReturn(snapshot);

        mvc.perform(signedIn(put("/api/editions/{id}/playback-state", 6)).content(
                "{\"positionMs\":90000,\"watched\":true,\"watchedAt\":\"2024-03-01T10:00:00Z\","
                    + "\"hiddenFromContinueWatching\":false,\"hiddenAt\":null}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.positionMs").value(90_000))
            .andExpect(jsonPath("$.watched").value(true))
            .andExpect(jsonPath("$.watchedAt").value("2024-03-01T10:00:00Z"))
            .andExpect(jsonPath("$.hiddenFromContinueWatching").value(false));
        verify(playback).restore(6L, snapshot);
    }

    @Test
    void restoreRejectedByTheServiceIsA400() throws Exception {
        when(playback.restore(6L, new PlaybackSnapshot(-1L, false, null, false, null)))
            .thenThrow(new BadRequestException("positionMs must be a non-negative number"));

        mvc.perform(signedIn(put("/api/editions/{id}/playback-state", 6)).content(
                "{\"positionMs\":-1,\"watched\":false,\"hiddenFromContinueWatching\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("positionMs must be a non-negative number"));
    }

    @Test
    void theNewEndpointsNeedTheCsrfHeaderAndASession() throws Exception {
        mvc.perform(put("/api/editions/{id}/continue-watching", 6).with(user("owner")).contentType(JSON)
                .content("{\"hidden\":true}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        mvc.perform(put("/api/editions/{id}/playback-state", 6).with(user("owner")).contentType(JSON)
                .content("{\"positionMs\":0}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        mvc.perform(put("/api/editions/{id}/continue-watching", 6).header("X-CineFilm", "1").contentType(JSON)
                .content("{\"hidden\":true}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(put("/api/editions/{id}/playback-state", 6).header("X-CineFilm", "1").contentType(JSON)
                .content("{\"positionMs\":0}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void preferencesPassTheFilmAndEveryRequestField() throws Exception {
        when(preferences.update(2L, new PreferencesRequest(20L, "720p", 200L, "12")))
            .thenReturn(new Preferences(20L, "720p", 200L, "12"));

        mvc.perform(signedIn(put("/api/films/{id}/preferences", 2))
                .content("{\"editionId\":20,\"quality\":\"720p\",\"audioStreamId\":200,\"subtitle\":\"12\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editionId").value(20))
            .andExpect(jsonPath("$.quality").value("720p"))
            .andExpect(jsonPath("$.audioStreamId").value(200))
            .andExpect(jsonPath("$.subtitle").value("12"));
        verify(preferences).update(2L, new PreferencesRequest(20L, "720p", 200L, "12"));
    }

    @Test
    void aMutatingCallWithoutTheCsrfHeaderIsForbiddenAndNeverReachesTheService() throws Exception {
        mvc.perform(put("/api/editions/{id}/progress", 5).with(user("owner")).contentType(JSON)
                .content("{\"positionMs\":1}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(put("/api/films/{id}/preferences", 2).header("X-CineFilm", "1").contentType(JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }
}
