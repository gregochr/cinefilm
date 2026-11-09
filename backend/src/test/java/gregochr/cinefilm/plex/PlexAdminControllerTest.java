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
import gregochr.cinefilm.plex.PlexDtos.LastImport;
import gregochr.cinefilm.plex.PlexDtos.PlexImportReport;
import gregochr.cinefilm.plex.PlexDtos.PlexImportStatus;
import gregochr.cinefilm.plex.PlexDtos.PlexProgress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlexAdminController.class)
@Import(SecurityConfig.class)
class PlexAdminControllerTest {

    private static final PlexImportReport REPORT = new PlexImportReport(10, 9, 1, 2, 9, 8, 7, 3, 4, 5, 6, 7, 8, 9,
        List.of("Bad"), List.of("Gone"), 11, 12, List.of("Bond"), 13);
    private static final PlexImportStatus IDLE = new PlexImportStatus(true, true, false, null,
        new LastImport(NOW.minusSeconds(60), NOW, null, REPORT));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PlexImportService imports;

    @Test
    void getReturnsTheStatusWithTheWholeReport() throws Exception {
        when(imports.status()).thenReturn(IDLE);

        mvc.perform(get("/api/admin/plex").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.reachable").value(true))
            .andExpect(jsonPath("$.running").value(false))
            .andExpect(jsonPath("$.progress").doesNotExist())
            .andExpect(jsonPath("$.lastImport.startedAt").value("2024-03-31T23:29:00Z"))
            .andExpect(jsonPath("$.lastImport.finishedAt").value("2024-03-31T23:30:00Z"))
            .andExpect(jsonPath("$.lastImport.error").doesNotExist())
            .andExpect(jsonPath("$.lastImport.report.plexFilms").value(10))
            .andExpect(jsonPath("$.lastImport.report.paired").value(9))
            .andExpect(jsonPath("$.lastImport.report.merged").value(1))
            .andExpect(jsonPath("$.lastImport.report.editionsMoved").value(2))
            .andExpect(jsonPath("$.lastImport.report.metadataApplied").value(9))
            .andExpect(jsonPath("$.lastImport.report.postersStored").value(8))
            .andExpect(jsonPath("$.lastImport.report.backdropsStored").value(7))
            .andExpect(jsonPath("$.lastImport.report.artworkFailures").value(3))
            .andExpect(jsonPath("$.lastImport.report.invalidArtPaths").value(4))
            .andExpect(jsonPath("$.lastImport.report.tmdbUnavailable").value(5))
            .andExpect(jsonPath("$.lastImport.report.watchedImported").value(6))
            .andExpect(jsonPath("$.lastImport.report.resumePointsImported").value(7))
            .andExpect(jsonPath("$.lastImport.report.keptNewer").value(8))
            .andExpect(jsonPath("$.lastImport.report.failures").value(9))
            .andExpect(jsonPath("$.lastImport.report.failedFilms[0]").value("Bad"))
            .andExpect(jsonPath("$.lastImport.report.notOnDisk[0]").value("Gone"))
            .andExpect(jsonPath("$.lastImport.report.notOnDiskTotal").value(11))
            .andExpect(jsonPath("$.lastImport.report.onDiskNotInPlex").value(12))
            .andExpect(jsonPath("$.lastImport.report.collections[0]").value("Bond"))
            .andExpect(jsonPath("$.lastImport.report.collectionsTotal").value(13));
    }

    @Test
    void getWhileRunningCarriesProgress() throws Exception {
        when(imports.status()).thenReturn(new PlexImportStatus(true, true, true, new PlexProgress(3, 10, "Heat"),
            null));

        mvc.perform(get("/api/admin/plex").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.progress.processed").value(3))
            .andExpect(jsonPath("$.progress.total").value(10))
            .andExpect(jsonPath("$.progress.current").value("Heat"))
            .andExpect(jsonPath("$.lastImport").doesNotExist());
    }

    @Test
    void postStartsTheImportAndAnswers202WithTheStatus() throws Exception {
        when(imports.status()).thenReturn(new PlexImportStatus(true, true, true, new PlexProgress(0, 0, null), null));

        mvc.perform(post("/api/admin/plex/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.running").value(true));

        verify(imports).start();
    }

    @Test
    void postWhileAScanOrImportRunsIs409() throws Exception {
        doThrow(new ConflictException("A scan or import is already running")).when(imports).start();

        mvc.perform(post("/api/admin/plex/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan or import is already running"));
    }

    @Test
    void postBeforeAnyScanIs409() throws Exception {
        doThrow(new ConflictException("Run a library scan first")).when(imports).start();

        mvc.perform(post("/api/admin/plex/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Run a library scan first"));
    }

    @Test
    void postWithPlexNotConfiguredIs404() throws Exception {
        doThrow(new NotFoundException("Plex is not configured")).when(imports).start();

        mvc.perform(post("/api/admin/plex/import").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("Plex is not configured"));
    }

    @Test
    void postWithoutTheCsrfHeaderIs403AndNeverStartsAnImport() throws Exception {
        mvc.perform(post("/api/admin/plex/import").with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verify(imports, never()).start();
    }

    @Test
    void signedOutBothRoutesAre401() throws Exception {
        mvc.perform(get("/api/admin/plex")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/admin/plex/import").header("X-CineFilm", "1")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));

        verify(imports, never()).start();
    }
}
