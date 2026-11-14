package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.TestData.NOW;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.credits.learn.DatasetStatus.State;
import gregochr.cinefilm.credits.learn.DatasetStatus.Waiting;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CreditsDatasetController.class)
@Import(SecurityConfig.class)
class CreditsDatasetControllerTest {

    private static final DatasetStatus WAITING = new DatasetStatus(true, State.WAITING, Waiting.REFRESHER, 850, 12,
        30, 1, 2, 805, null, 3, 40_000_000_000L, 15_000_000_000L, NOW.minusSeconds(60), null, null);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CreditsDatasetJob job;

    @Test
    void getAnswersTheWholeStatus() throws Exception {
        when(job.status()).thenReturn(WAITING);

        mvc.perform(get("/api/admin/credits/dataset").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.state").value("WAITING"))
            .andExpect(jsonPath("$.waitingFor").value("REFRESHER"))
            .andExpect(jsonPath("$.targets").value(850))
            .andExpect(jsonPath("$.alreadyComplete").value(12))
            .andExpect(jsonPath("$.complete").value(30))
            .andExpect(jsonPath("$.failed").value(1))
            .andExpect(jsonPath("$.skipped").value(2))
            .andExpect(jsonPath("$.remaining").value(805))
            .andExpect(jsonPath("$.currentMediaFileId").doesNotExist())
            .andExpect(jsonPath("$.refresherDue").value(3))
            .andExpect(jsonPath("$.freeBytes").value(40_000_000_000L))
            .andExpect(jsonPath("$.minFreeBytes").value(15_000_000_000L))
            .andExpect(jsonPath("$.startedAt").value("2024-03-31T23:29:00Z"))
            .andExpect(jsonPath("$.finishedAt").doesNotExist())
            .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void startAnswers202WithTheStatus() throws Exception {
        when(job.start()).thenReturn(WAITING);

        mvc.perform(post("/api/admin/credits/dataset/start").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("WAITING"))
            .andExpect(jsonPath("$.targets").value(850));
    }

    @Test
    void startWhenTheJobIsOffIs404InTheUsualErrorShape() throws Exception {
        when(job.start()).thenThrow(new NotFoundException("The dataset job is off"));

        mvc.perform(post("/api/admin/credits/dataset/start").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("The dataset job is off"));
    }

    @Test
    void startWhileRunningIs409InTheUsualErrorShape() throws Exception {
        doThrow(new ConflictException("The dataset job is already running")).when(job).start();

        mvc.perform(post("/api/admin/credits/dataset/start").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("The dataset job is already running"));
    }

    @Test
    void stopAnswers202WithTheStatus() throws Exception {
        when(job.stop()).thenReturn(new DatasetStatus(true, State.STOPPED, null, 850, 0, 4, 0, 0, 846, null, 0, null,
            15_000_000_000L, NOW, NOW, null));

        mvc.perform(post("/api/admin/credits/dataset/stop").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.state").value("STOPPED"))
            .andExpect(jsonPath("$.waitingFor").doesNotExist())
            .andExpect(jsonPath("$.freeBytes").doesNotExist());
    }

    @Test
    void getWithoutASessionIs401() throws Exception {
        mvc.perform(get("/api/admin/credits/dataset")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void bothPostsWithoutASessionAre401AndNeverReachTheJob() throws Exception {
        mvc.perform(post("/api/admin/credits/dataset/start").header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/admin/credits/dataset/stop").header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));

        verify(job, never()).start();
        verify(job, never()).stop();
    }

    @Test
    void bothPostsWithoutTheCsrfHeaderAre403AndNeverReachTheJob() throws Exception {
        mvc.perform(post("/api/admin/credits/dataset/start").with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));
        mvc.perform(post("/api/admin/credits/dataset/stop").with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verify(job, never()).start();
        verify(job, never()).stop();
    }
}
