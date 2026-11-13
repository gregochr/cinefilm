package gregochr.cinefilm.plex;

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
import gregochr.cinefilm.plex.PlexCreditsDtos.LastCreditsImport;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsProgress;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsReport;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsStatus;
import gregochr.cinefilm.plex.PlexCreditsDtos.SkipCount;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlexCreditsController.class)
@Import(SecurityConfig.class)
class PlexCreditsControllerTest {

    private static final PlexCreditsReport REPORT = new PlexCreditsReport(1087, 1030, 860, 2,
        List.of(new SkipCount("MULTI_PART", "Films split into parts", 3)));
    private static final PlexCreditsStatus IDLE = new PlexCreditsStatus(false, null,
        new LastCreditsImport(NOW.minusSeconds(60), NOW, null, REPORT));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlexCreditsImportService imports;

    @Test
    void getReturnsTheStatusWithTheWholeReport() throws Exception {
        when(imports.status()).thenReturn(IDLE);

        mvc.perform(get("/api/admin/plex/credits").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.progress").doesNotExist())
            .andExpect(jsonPath("$.lastImport.startedAt").value("2024-03-31T23:29:00Z"))
            .andExpect(jsonPath("$.lastImport.finishedAt").value("2024-03-31T23:30:00Z"))
            .andExpect(jsonPath("$.lastImport.error").doesNotExist())
            .andExpect(jsonPath("$.lastImport.report.plexFilms").value(1087))
            .andExpect(jsonPath("$.lastImport.report.matchedFilms").value(1030))
            .andExpect(jsonPath("$.lastImport.report.imported").value(860))
            .andExpect(jsonPath("$.lastImport.report.unmatched").value(2))
            .andExpect(jsonPath("$.lastImport.report.skipped[0].reason").value("MULTI_PART"))
            .andExpect(jsonPath("$.lastImport.report.skipped[0].description").value("Films split into parts"))
            .andExpect(jsonPath("$.lastImport.report.skipped[0].count").value(3));
    }

    @Test
    void getWhileRunningCarriesProgress() throws Exception {
        when(imports.status()).thenReturn(new PlexCreditsStatus(true, new PlexCreditsProgress(3, 10), null));

        mvc.perform(get("/api/admin/plex/credits").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.progress.processed").value(3))
            .andExpect(jsonPath("$.progress.total").value(10))
            .andExpect(jsonPath("$.lastImport").doesNotExist());
    }

    @Test
    void postStartsTheImportAndAnswers202WithTheStatus() throws Exception {
        when(imports.status()).thenReturn(new PlexCreditsStatus(true, new PlexCreditsProgress(0, 0), null));

        mvc.perform(post("/api/admin/plex/credits/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.running").value(true));

        verify(imports).start();
    }

    @Test
    void postWhileOneRunsIs409() throws Exception {
        doThrow(new ConflictException("A credits import is already running.")).when(imports).start();

        mvc.perform(post("/api/admin/plex/credits/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A credits import is already running."));
    }

    @Test
    void postWhileThePlexImportRunsIs409WithItsOwnReason() throws Exception {
        doThrow(new ConflictException("Import credits after the Plex import finishes.")).when(imports).start();

        mvc.perform(post("/api/admin/plex/credits/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Import credits after the Plex import finishes."));
    }

    @Test
    void postWithPlexNotConfiguredIs404() throws Exception {
        doThrow(new NotFoundException("Plex is not configured")).when(imports).start();

        mvc.perform(post("/api/admin/plex/credits/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Plex is not configured"));
    }

    @Test
    void postWithoutTheCsrfHeaderIs403AndNeverStartsAnImport() throws Exception {
        mvc.perform(post("/api/admin/plex/credits/import").with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verify(imports, never()).start();
    }

    @Test
    void signedOutBothRoutesAre401() throws Exception {
        mvc.perform(get("/api/admin/plex/credits")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/admin/plex/credits/import").header("X-CineFilm", "1"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));

        verify(imports, never()).start();
    }
}
