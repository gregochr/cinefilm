package gregochr.cinefilm.summary;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.auth.SecurityConfig;
import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import gregochr.cinefilm.summary.AdminSummaryDtos.AdminSummary;
import gregochr.cinefilm.summary.AdminSummaryDtos.Library;
import gregochr.cinefilm.summary.AdminSummaryDtos.Todo;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminSummaryController.class)
@Import(SecurityConfig.class)
class AdminSummaryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AdminSummaryService summaries;

    @Test
    void theSummaryIsServedWithExactlyTheShapeTheClientReads() throws Exception {
        when(summaries.summary()).thenReturn(new AdminSummary(new Todo(3, 2, 7, 11), 18, 2,
            new Library(321, Instant.parse("2024-03-30T02:00:00Z"), null), new PlaybackCounts(40, 31),
            List.of(new PlayingNow("3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b", 11, "Blade Runner",
                "Mozilla/5.0 (Macintosh)", true, "home", "720p"))));

        mvc.perform(get("/api/admin/summary").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"todo":{"pickFilm":3,"severalFiles":2,"fullMissing":7,"forcedQuestion":11},
                 "subtitlesUnresolved":18,"live":2,
                 "library":{"films":321,"lastScanFinishedAt":"2024-03-30T02:00:00Z","lastScanError":null},
                 "playback":{"films":40,"smooth":31},
                 "playingNow":[{"playbackId":"3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b","editionId":11,
                   "filmTitle":"Blade Runner","device":"Mozilla/5.0 (Macintosh)","touch":true,"environment":"home",
                   "quality":"720p"}]}
                """, true));
    }

    @Test
    void anEntryFromAReportWithNoTouchHintSerialisesTouchAsNull() throws Exception {
        when(summaries.summary()).thenReturn(new AdminSummary(new Todo(0, 0, 0, 0), 0, 0, new Library(0, null, null),
            new PlaybackCounts(0, 0),
            List.of(new PlayingNow("3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b", 11, "Blade Runner",
                "Mozilla/5.0 (Macintosh)", null, "home", "720p"))));

        mvc.perform(get("/api/admin/summary").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.playingNow[0].touch").value((Object) null))
            .andExpect(jsonPath("$.playingNow[0].device").value("Mozilla/5.0 (Macintosh)"));
    }

    @Test
    void aNeverScannedLibraryAndAnEmptyPlayingNowSerialiseAsNullAndEmpty() throws Exception {
        when(summaries.summary()).thenReturn(new AdminSummary(new Todo(0, 0, 0, 0), 0, 0, new Library(0, null, null),
            new PlaybackCounts(0, 0), List.of()));

        mvc.perform(get("/api/admin/summary").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.library.lastScanFinishedAt").value((Object) null))
            .andExpect(jsonPath("$.playingNow").isEmpty());
    }

    @Test
    void signedOutIs401() throws Exception {
        mvc.perform(get("/api/admin/summary"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void aGetWithoutTheCsrfHeaderSucceeds() throws Exception {
        when(summaries.summary()).thenReturn(new AdminSummary(new Todo(0, 0, 0, 0), 5, 0, new Library(0, null, null),
            new PlaybackCounts(0, 0), List.of()));

        mvc.perform(get("/api/admin/summary").with(user("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subtitlesUnresolved").value(5));
    }
}
