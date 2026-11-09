package gregochr.cinefilm.playback;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class PlaybackApiTest extends ApiTestBase {

    private Film film;
    private Edition withMarker;
    private Edition withoutMarker;

    @BeforeEach
    void data() {
        film = film(null, "Blade Runner", 1982, NOW, "Sci-fi");
        withMarker = edition(film, null, "The Final Cut", 100 * MIN, 90 * MIN);
        withoutMarker = edition(film, null, "Theatrical Cut", 100 * MIN, null);
        file(withMarker, "/nowhere/final.mp4");
        file(withoutMarker, "/nowhere/theatrical.mp4");
        save(film);
    }

    private String progressUrl(Edition edition) {
        return "/api/editions/" + edition.getId() + "/progress";
    }

    @Test
    void progressBelowTheMarkerIsSavedButNotWatched() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 89 * MIN + "}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.positionMs").value(89 * MIN))
            .andExpect(jsonPath("$.watched").value(false));
        PlaybackState state = states.findById(withMarker.getId()).orElseThrow();
        assertThat(state.getPositionMs()).isEqualTo(89 * MIN);
        assertThat(state.getLastPlayedAt()).isNotNull();
        assertThat(state.isWatched()).isFalse();
    }

    @Test
    void progressAtTheMarkerMakesItWatchedEvenBelowNinetyTwoPercent() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 90 * MIN + "}"))
            .andExpect(jsonPath("$.watched").value(true))
            .andExpect(jsonPath("$.positionMs").value(0));
        PlaybackState state = states.findById(withMarker.getId()).orElseThrow();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getPositionMs()).isZero();
        assertThat(state.getWatchedAt()).isNotNull();
    }

    @Test
    void withoutAMarkerNinetyTwoPercentDecides() throws Exception {
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 91 * MIN + "}"))
            .andExpect(jsonPath("$.watched").value(false));
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 92 * MIN + "}"))
            .andExpect(jsonPath("$.watched").value(true));
    }

    @Test
    void watchedIsPerEditionAndStaysWatchedOnARewatch() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 95 * MIN + "}"))
            .andExpect(jsonPath("$.watched").value(true));
        assertThat(states.findById(withoutMarker.getId())).isEmpty();
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 5 * MIN + "}"))
            .andExpect(jsonPath("$.positionMs").value(5 * MIN))
            .andExpect(jsonPath("$.watched").value(true));
    }

    @Test
    void reachingTheThresholdResetsThePositionAndLeavesContinueWatching() throws Exception {
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 50 * MIN + "}"));
        mvc.perform(get("/api/home")).andExpect(jsonPath("$.hero.title").value("Blade Runner"));
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 95 * MIN + "}"));
        // credits keep playing: still watched, still position 0
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 99 * MIN + "}"))
            .andExpect(jsonPath("$.positionMs").value(0));
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.hero").value(nullValue()))
            .andExpect(jsonPath("$.continueWatching", hasSize(0)));
    }

    @Test
    void aWatchedEditionWithNewProgressIsInContinueWatchingAgain() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 95 * MIN + "}"));
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 7 * MIN + "}"));
        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.hero.title").value("Blade Runner"))
            .andExpect(jsonPath("$.hero.watched").value(true))
            .andExpect(jsonPath("$.hero.progress.positionMs").value(7 * MIN));
    }

    @Test
    void markingWatchedResetsThePosition() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 10 * MIN + "}"));
        mvc.perform(post("/api/editions/" + withMarker.getId() + "/watched").content("{\"watched\":true}"));
        assertThat(states.findById(withMarker.getId()).orElseThrow().getPositionMs()).isZero();
    }

    @Test
    void progressRemembersTheLastEditionPlayed() throws Exception {
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":1000}")).andExpect(status().isOk());
        assertThat(preferences.findById(film.getId()).orElseThrow().getLastEditionId())
            .isEqualTo(withoutMarker.getId());
        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(jsonPath("$.preferences.editionId").value(withoutMarker.getId()));
    }

    @Test
    void badProgressBodiesAreRejected() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("positionMs must be a non-negative number"));
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":-5}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("positionMs must be a non-negative number"));
        mvc.perform(put(progressUrl(withMarker)).content("not json")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void progressForAnUnknownEditionIs404() throws Exception {
        mvc.perform(put("/api/editions/777777/progress").content("{\"positionMs\":1}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void progressWithoutTheHeaderIsForbidden() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put(progressUrl(withMarker)).contentType("application/json")
                .with(user("owner")).content("{\"positionMs\":1}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void markWatchedAndUnwatched() throws Exception {
        String url = "/api/editions/" + withMarker.getId() + "/watched";
        mvc.perform(post(url).content("{\"watched\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.watched").value(true));
        assertThat(states.findById(withMarker.getId()).orElseThrow().getWatchedAt()).isNotNull();
        mvc.perform(post(url).content("{\"watched\":true}")).andExpect(jsonPath("$.watched").value(true));

        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 95 * MIN + "}"));
        mvc.perform(post(url).content("{\"watched\":false}"))
            .andExpect(jsonPath("$.watched").value(false));
        PlaybackState state = states.findById(withMarker.getId()).orElseThrow();
        assertThat(state.isWatched()).isFalse();
        assertThat(state.getPositionMs()).isZero();
        assertThat(state.getWatchedAt()).isNull();
    }

    @Test
    void watchedBodyMustBeABoolean() throws Exception {
        String url = "/api/editions/" + withMarker.getId() + "/watched";
        mvc.perform(post(url).content("{}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("watched must be true or false"));
        mvc.perform(post(url).content("{\"watched\":\"maybe\"}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid request"));
    }

    @Test
    void watchedForAnUnknownEditionIs404() throws Exception {
        mvc.perform(post("/api/editions/777777/watched").content("{\"watched\":true}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void watchedWithoutTheHeaderIsForbidden() throws Exception {
        mvc.perform(MockMvcRequestBuilders
                .post("/api/editions/" + withMarker.getId() + "/watched").contentType("application/json")
                .with(user("owner")).content("{\"watched\":true}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    // Continue watching: remove, undo, come back

    private String hiddenUrl(Edition edition) {
        return "/api/editions/" + edition.getId() + "/continue-watching";
    }

    @Test
    void aRemovedFilmLeavesHomeButKeepsItsPositionAndReturnsWhenPlayedAgain() throws Exception {
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 50 * MIN + "}"));

        mvc.perform(put(hiddenUrl(withoutMarker)).content("{\"hidden\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(true));

        mvc.perform(get("/api/home"))
            .andExpect(jsonPath("$.hero").value(nullValue()))
            .andExpect(jsonPath("$.continueWatching", hasSize(0)));
        PlaybackState state = states.findById(withoutMarker.getId()).orElseThrow();
        assertThat(state.getPositionMs()).isEqualTo(50 * MIN);
        assertThat(state.getHiddenAt()).isNotNull();
        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(jsonPath("$.progress.positionMs").value(50 * MIN));

        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 51 * MIN + "}"));
        mvc.perform(get("/api/home")).andExpect(jsonPath("$.hero.progress.positionMs").value(51 * MIN));
        assertThat(states.findById(withoutMarker.getId()).orElseThrow().isHiddenFromContinueWatching()).isFalse();
    }

    @Test
    void unhidingPutsTheFilmBackInTheRow() throws Exception {
        mvc.perform(put(progressUrl(withoutMarker)).content("{\"positionMs\":" + 50 * MIN + "}"));
        mvc.perform(put(hiddenUrl(withoutMarker)).content("{\"hidden\":true}"));

        mvc.perform(put(hiddenUrl(withoutMarker)).content("{\"hidden\":false}"))
            .andExpect(jsonPath("$.hidden").value(false));

        mvc.perform(get("/api/home")).andExpect(jsonPath("$.hero.title").value("Blade Runner"));
        assertThat(states.findById(withoutMarker.getId()).orElseThrow().getHiddenAt()).isNull();
    }

    @Test
    void hidingAnEditionThatWasNeverPlayedCreatesNoRow() throws Exception {
        mvc.perform(put(hiddenUrl(withoutMarker)).content("{\"hidden\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hidden").value(false));
        assertThat(states.findById(withoutMarker.getId())).isEmpty();
    }

    @Test
    void hiddenBodyMustBeABooleanAndTheEditionMustExist() throws Exception {
        mvc.perform(put(hiddenUrl(withoutMarker)).content("{}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("hidden must be true or false"));
        mvc.perform(put(hiddenUrl(withoutMarker)).content("{\"hidden\":\"maybe\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Invalid request"));
        mvc.perform(put("/api/editions/777777/continue-watching").content("{\"hidden\":true}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such edition"));
    }

    @Test
    void markingWatchedReturnsTheStateBeforeAndRestoringItUndoesItExactly() throws Exception {
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 95 * MIN + "}"));
        mvc.perform(put(progressUrl(withMarker)).content("{\"positionMs\":" + 7 * MIN + "}"));
        Instant watchedAt = states.findById(withMarker.getId()).orElseThrow().getWatchedAt();
        mvc.perform(put(hiddenUrl(withMarker)).content("{\"hidden\":true}"));
        String url = "/api/editions/" + withMarker.getId() + "/watched";

        String body = mvc.perform(post(url).content("{\"watched\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.previous.positionMs").value(7 * MIN))
            .andExpect(jsonPath("$.previous.watched").value(true))
            .andExpect(jsonPath("$.previous.hiddenFromContinueWatching").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(states.findById(withMarker.getId()).orElseThrow().getPositionMs()).isZero();

        String previous = com.jayway.jsonpath.JsonPath.<java.util.Map<String, Object>>read(body, "$.previous")
            .entrySet().stream()
            .map(e -> "\"" + e.getKey() + "\":" + (e.getValue() instanceof String s ? "\"" + s + "\""
                : String.valueOf(e.getValue())))
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        mvc.perform(put("/api/editions/" + withMarker.getId() + "/playback-state").content(previous))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.positionMs").value(7 * MIN));

        PlaybackState state = states.findById(withMarker.getId()).orElseThrow();
        assertThat(state.getPositionMs()).isEqualTo(7 * MIN);
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getWatchedAt()).isEqualTo(watchedAt);
        assertThat(state.isHiddenFromContinueWatching()).isTrue();
        assertThat(state.getHiddenAt()).isNotNull();
    }

    @Test
    void restoreValidatesThePositionAndTheEditionAndNeedsTheHeader() throws Exception {
        String url = "/api/editions/" + withMarker.getId() + "/playback-state";
        mvc.perform(put(url).content("{\"positionMs\":-1,\"watched\":false,\"hiddenFromContinueWatching\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("positionMs must be a non-negative number"));
        mvc.perform(put("/api/editions/777777/playback-state")
                .content("{\"positionMs\":0,\"watched\":false,\"hiddenFromContinueWatching\":false}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No such edition"));
        mvc.perform(MockMvcRequestBuilders.put(url).contentType("application/json").with(user("owner"))
                .content("{\"positionMs\":0}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    // preferences

    @Test
    void preferencesAreRememberedPerFilm() throws Exception {
        Long audioId = film.getEditions().get(0).originalFile().getAudioStreams().get(1).getId();
        mvc.perform(put("/api/films/{id}/preferences", film.getId())
                .content("{\"editionId\":" + withoutMarker.getId() + ",\"quality\":\"Original\","
                    + "\"audioStreamId\":" + audioId + ",\"subtitle\":\"off\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editionId").value(withoutMarker.getId()))
            .andExpect(jsonPath("$.quality").value("original"))
            .andExpect(jsonPath("$.audioStreamId").value(audioId))
            .andExpect(jsonPath("$.subtitle").value("off"));

        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(jsonPath("$.preferences.editionId").value(withoutMarker.getId()))
            .andExpect(jsonPath("$.preferences.quality").value("original"))
            .andExpect(jsonPath("$.preferences.audioStreamId").value(audioId));
    }

    @Test
    void partialUpdatesLeaveOtherChoicesAlone() throws Exception {
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{\"quality\":\"720p\"}"))
            .andExpect(jsonPath("$.quality").value("720p"))
            .andExpect(jsonPath("$.editionId").value(withMarker.getId()));
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{\"subtitle\":\"12\"}"))
            .andExpect(jsonPath("$.quality").value("720p"))
            .andExpect(jsonPath("$.subtitle").value("12"));
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{\"editionId\":"
                + withoutMarker.getId() + "}"))
            .andExpect(jsonPath("$.editionId").value(withoutMarker.getId()))
            .andExpect(jsonPath("$.subtitle").value("12"));
        FilmPreference stored = preferences.findById(film.getId()).orElseThrow();
        assertThat(stored.getDefaultEditionId()).isEqualTo(withoutMarker.getId());
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{\"editionId\":"
                + withMarker.getId() + "}"))
            .andExpect(jsonPath("$.editionId").value(withMarker.getId()));
        assertThat(preferences.findById(film.getId()).orElseThrow().getDefaultEditionId())
            .isEqualTo(withoutMarker.getId());
    }

    @Test
    void emptyPreferencesBodyReturnsDefaults() throws Exception {
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quality").value("auto"))
            .andExpect(jsonPath("$.subtitle").value("off"))
            .andExpect(jsonPath("$.editionId").value(withMarker.getId()))
            .andExpect(jsonPath("$.audioStreamId").exists());
    }

    @Test
    void invalidPreferencesAreRejected() throws Exception {
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{\"quality\":\"4k\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error")
                .value("quality must be one of [auto, original, 1080p, 720p, 480p, 360p, 240p]"));
        mvc.perform(put("/api/films/{id}/preferences", film.getId()).content("{\"editionId\":999999}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Edition does not belong to this film"));
    }

    @Test
    void preferencesForAnUnknownFilmIs404() throws Exception {
        mvc.perform(put("/api/films/{id}/preferences", 888888).content("{}")).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such film"));
    }

    @Test
    void preferencesWithoutTheHeaderIsForbidden() throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/api/films/{id}/preferences", film.getId())
                .contentType("application/json")
                .with(user("owner")).content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
    }

    @Test
    void aStoredEditionThatNoLongerExistsFallsBackToTheFirst() throws Exception {
        FilmPreference stale = new FilmPreference(film.getId());
        stale.setLastEditionId(123456L);
        preferences.save(stale);
        mvc.perform(get("/api/films/{id}", film.getId()))
            .andExpect(jsonPath("$.preferences.editionId").value(withMarker.getId()))
            .andExpect(jsonPath("$.editions", hasSize(2)))
            .andExpect(jsonPath("$.preferences.quality").value("auto"))
            .andExpect(jsonPath("$.collection").value(nullValue()));
    }
}
