package gregochr.cinefilm.summary;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import gregochr.cinefilm.health.PlaybackHealthService;
import gregochr.cinefilm.library.AdminDtos.LastScanDto;
import gregochr.cinefilm.library.AdminService;
import gregochr.cinefilm.library.AdminService.SummaryFigures;
import gregochr.cinefilm.subtitle.ForcedNeedService;
import gregochr.cinefilm.subtitle.FullNeedService;
import gregochr.cinefilm.summary.AdminSummaryDtos.AdminSummary;
import gregochr.cinefilm.transcode.SessionManager;
import gregochr.cinefilm.transcode.TestSessions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class AdminSummaryServiceTest {

    private static final Instant FINISHED = Instant.parse("2024-03-30T02:00:00Z");

    @Mock
    private AdminService admin;

    @Mock
    private FullNeedService fullNeed;

    @Mock
    private ForcedNeedService forcedNeed;

    @Mock
    private SessionManager sessions;

    @Mock
    private PlaybackHealthService health;

    private AdminSummaryService service;

    @BeforeEach
    void setUp() {
        service = new AdminSummaryService(admin, fullNeed, forcedNeed, sessions, health);
    }

    private static SummaryFigures figures(long needsMatch, long severalFiles, long films, LastScanDto last) {
        return new SummaryFigures(needsMatch, severalFiles, films, last);
    }

    private static LastScanDto finishedAt(Instant when) {
        return new LastScanDto(when.minusSeconds(60), when, "nightly", 10, 0, 0, null);
    }

    @Test
    void aFailedLastScanReportsItsErrorAndFinishTime() {
        LastScanDto failed = new LastScanDto(FINISHED.minusSeconds(60), FINISHED, "nightly", 0, 0, 0,
            "Library folder is not readable");
        stubEverything(figures(0, 0, 12, failed), 0, 0);

        AdminSummary summary = service.summary();

        assertThat(summary.library().lastScanError()).isEqualTo("Library folder is not readable");
        assertThat(summary.library().lastScanFinishedAt()).isEqualTo(FINISHED);
        assertThat(summary.library().films()).isEqualTo(12);
    }

    @Test
    void theSummaryRunsInOneReadOnlyTransaction() throws NoSuchMethodException {
        Transactional transactional = AdminSummaryService.class.getMethod("summary")
            .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    private void stubEverything(SummaryFigures library, int full, int forced) {
        when(admin.summaryFigures()).thenReturn(library);
        when(fullNeed.unresolvedCount()).thenReturn(full);
        when(forcedNeed.unresolvedCount()).thenReturn(forced);
        when(sessions.sessions()).thenReturn(List.of());
        when(health.playbackCounts()).thenReturn(new PlaybackCounts(0, 0));
        when(health.playingNow()).thenReturn(List.of());
    }

    @Test
    void everyFieldIsReadFromItsOwnSource() {
        PlayingNow playing = new PlayingNow("3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b", 11, "Blade Runner", "Mac", true,
            "home", "720p");
        when(admin.summaryFigures()).thenReturn(figures(3, 2, 321, finishedAt(FINISHED)));
        when(fullNeed.unresolvedCount()).thenReturn(7);
        when(forcedNeed.unresolvedCount()).thenReturn(11);
        when(sessions.sessions()).thenReturn(List.of(
            TestSessions.sessionRow("a", "Film", 1, "720p", 1.0, 2, NOW, false),
            TestSessions.sessionRow("b", "Film", 2, "480p", 1.0, 2, NOW, false)));
        when(health.playbackCounts()).thenReturn(new PlaybackCounts(40, 31));
        when(health.playingNow()).thenReturn(List.of(playing));

        AdminSummary summary = service.summary();

        assertThat(summary.todo().pickFilm()).isEqualTo(3);
        assertThat(summary.todo().severalFiles()).isEqualTo(2);
        assertThat(summary.todo().fullMissing()).isEqualTo(7);
        assertThat(summary.todo().forcedQuestion()).isEqualTo(11);
        assertThat(summary.subtitlesUnresolved()).isEqualTo(18);
        assertThat(summary.live()).isEqualTo(2);
        assertThat(summary.library().films()).isEqualTo(321);
        assertThat(summary.library().lastScanFinishedAt()).isEqualTo(FINISHED);
        assertThat(summary.library().lastScanError()).isNull();
        assertThat(summary.playback()).isEqualTo(new PlaybackCounts(40, 31));
        assertThat(summary.playingNow()).containsExactly(playing);
    }

    @Test
    void subtitlesUnresolvedIsTheSumOfBothTabs() {
        stubEverything(figures(0, 0, 1, null), 4, 9);

        AdminSummary summary = service.summary();

        assertThat(summary.todo().fullMissing()).isEqualTo(4);
        assertThat(summary.todo().forcedQuestion()).isEqualTo(9);
        assertThat(summary.subtitlesUnresolved()).isEqualTo(13);
    }

    @Test
    void anEmptyLibraryThatWasNeverScannedHasNoScanTime() {
        stubEverything(figures(0, 0, 0, null), 0, 0);

        AdminSummary summary = service.summary();

        assertThat(summary.library().films()).isZero();
        assertThat(summary.library().lastScanFinishedAt()).isNull();
        assertThat(summary.library().lastScanError()).isNull();
        assertThat(summary.live()).isZero();
        assertThat(summary.playingNow()).isEmpty();
    }
}
