package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.file;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Progress upserts and watched transitions persist (committed, not rolled back) on PostgreSQL. */
@AutoConfigureMockMvc
class PlaybackStateIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private FilmRepository films;

    @Autowired
    private PlaybackStateRepository states;

    @Autowired
    private FilmPreferenceRepository preferences;

    private long editionId;

    @BeforeEach
    void data() {
        preferences.deleteAll();
        states.deleteAll();
        films.deleteAll();
        Film film = film(null, "Blade Runner", 1982, NOW, "Sci-fi");
        Edition edition = edition(film, null, "The Final Cut", 100 * MIN, 90 * MIN);
        file(edition, "/nowhere/final.mp4");
        films.save(film);
        editionId = film.getEditions().get(0).getId();
    }

    private void send(boolean post, String path, String body) throws Exception {
        String url = "/api/editions/" + editionId + path;
        var request = post ? MockMvcRequestBuilders.post(url) : MockMvcRequestBuilders.put(url);
        mvc.perform(request.with(user("owner").roles("OWNER")).header("X-CineFilm", "1")
                .contentType("application/json").content(body))
            .andExpect(status().isOk());
    }

    @Test
    void progressUpsertsOneRowAndKeepsTheLatestPosition() throws Exception {
        send(false, "/progress", "{\"positionMs\":" + 10 * MIN + "}");
        send(false, "/progress", "{\"positionMs\":" + 20 * MIN + "}");

        assertThat(states.count()).isEqualTo(1);
        PlaybackState state = states.findById(editionId).orElseThrow();
        assertThat(state.getPositionMs()).isEqualTo(20 * MIN);
        assertThat(state.isWatched()).isFalse();
        assertThat(state.getLastPlayedAt()).isNotNull();
    }

    @Test
    void reachingTheCreditsMarkerMarksWatchedAndResetsPosition() throws Exception {
        send(false, "/progress", "{\"positionMs\":" + 50 * MIN + "}");
        send(false, "/progress", "{\"positionMs\":" + 90 * MIN + "}");

        PlaybackState state = states.findById(editionId).orElseThrow();
        assertThat(state.isWatched()).isTrue();
        assertThat(state.getWatchedAt()).isNotNull();
        assertThat(state.getPositionMs()).isZero();
    }

    @Test
    void unwatchingClearsTheWatchedFlagAndTimestamp() throws Exception {
        send(true, "/watched", "{\"watched\":true}");
        assertThat(states.findById(editionId).orElseThrow().isWatched()).isTrue();

        send(true, "/watched", "{\"watched\":false}");

        PlaybackState state = states.findById(editionId).orElseThrow();
        assertThat(state.isWatched()).isFalse();
        assertThat(state.getWatchedAt()).isNull();
        assertThat(state.getPositionMs()).isZero();
    }
}
