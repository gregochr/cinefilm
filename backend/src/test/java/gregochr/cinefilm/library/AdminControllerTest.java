package gregochr.cinefilm.library;

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
import gregochr.cinefilm.library.AdminDtos.Attention;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.CandidateDto;
import gregochr.cinefilm.library.AdminDtos.CountsDto;
import gregochr.cinefilm.library.AdminDtos.ProgressDto;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    private static final ScanStatusDto IDLE = new ScanStatusDto(false, "/media", null, null,
        new CountsDto(5, 1, 2, 3, 4, 6, 7));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AdminService admin;

    @MockitoBean
    private ScanService scans;

    @Test
    void getScanReturnsTheStatusThePollingPanelReads() throws Exception {
        when(admin.status()).thenReturn(new ScanStatusDto(true, "/media", new ProgressDto(3, 10, "Heat.m4v"), null,
            new CountsDto(5, 1, 2, 3, 4, 6, 7)));

        mvc.perform(get("/api/admin/scan").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.running").value(true))
            .andExpect(jsonPath("$.folder").value("/media"))
            .andExpect(jsonPath("$.progress.processed").value(3))
            .andExpect(jsonPath("$.progress.total").value(10))
            .andExpect(jsonPath("$.progress.current").value("Heat.m4v"))
            .andExpect(jsonPath("$.counts.films").value(5))
            .andExpect(jsonPath("$.counts.newFiles").value(1))
            .andExpect(jsonPath("$.counts.missing").value(2))
            .andExpect(jsonPath("$.counts.protectedFiles").value(3))
            .andExpect(jsonPath("$.counts.needsConversion").value(4))
            .andExpect(jsonPath("$.counts.needsMatch").value(6))
            .andExpect(jsonPath("$.counts.unreadable").value(7));
    }

    @Test
    void postScanStartsAnAdminScanAndAnswers202WithTheStatus() throws Exception {
        when(admin.status()).thenReturn(IDLE);

        mvc.perform(post("/api/admin/scan").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.folder").value("/media"));

        verify(scans).start("admin");
    }

    @Test
    void postScanWhileOneIsRunningIs409WithTheMessage() throws Exception {
        doThrow(new ScanAlreadyRunningException()).when(scans).start("admin");

        mvc.perform(post("/api/admin/scan").with(user("owner")).header("X-CineFilm", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("A scan is already running"));
    }

    @Test
    void postScanWithoutTheCsrfHeaderIs403AndNeverStartsAScan() throws Exception {
        mvc.perform(post("/api/admin/scan").with(user("owner")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Missing X-CineFilm header"));

        verify(scans, never()).start("admin");
    }

    @Test
    void signedOutEveryAdminRouteIs401() throws Exception {
        mvc.perform(get("/api/admin/scan")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(post("/api/admin/scan").header("X-CineFilm", "1")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
        mvc.perform(get("/api/admin/attention")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));

        verify(scans, never()).start("admin");
    }

    @Test
    void getAttentionReturnsTheItemsWithTheirCandidates() throws Exception {
        when(admin.attention()).thenReturn(new Attention(List.of(new AttentionItem("needs-match", 9L, "Dune", null,
            "2 possible matches", List.of("Dune.m4v"), List.of(new CandidateDto(841, "Dune", 1984, 136, "/p.jpg"))))));

        mvc.perform(get("/api/admin/attention").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].kind").value("needs-match"))
            .andExpect(jsonPath("$.items[0].filmId").value(9))
            .andExpect(jsonPath("$.items[0].title").value("Dune"))
            .andExpect(jsonPath("$.items[0].year").doesNotExist())
            .andExpect(jsonPath("$.items[0].detail").value("2 possible matches"))
            .andExpect(jsonPath("$.items[0].files[0]").value("Dune.m4v"))
            .andExpect(jsonPath("$.items[0].candidates[0].tmdbId").value(841))
            .andExpect(jsonPath("$.items[0].candidates[0].runtimeMinutes").value(136))
            .andExpect(jsonPath("$.items[0].candidates[0].posterPath").value("/p.jpg"));
    }
}
