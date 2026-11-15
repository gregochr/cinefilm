package gregochr.cinefilm.summary;

import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import gregochr.cinefilm.health.PlaybackHealthService;
import gregochr.cinefilm.library.AdminDtos.Attention;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.CountsDto;
import gregochr.cinefilm.library.AdminDtos.LastScanDto;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import gregochr.cinefilm.library.AdminService;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import gregochr.cinefilm.subtitle.ForcedNeedService;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedCountsDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import gregochr.cinefilm.subtitle.FullNeedService;
import gregochr.cinefilm.summary.AdminSummaryDtos.AdminSummary;
import gregochr.cinefilm.transcode.SessionDtos.SessionRow;
import gregochr.cinefilm.transcode.SessionManager;
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

    private static AttentionItem item(String kind) {
        return new AttentionItem(kind, 1L, "A film", 2000, "detail", List.of(), List.of(), false);
    }

    private static ScanStatusDto scan(long films, LastScanDto last) {
        return new ScanStatusDto(false, "/media", null, last, new CountsDto(films, 0, 0, 0, 0, 0, 0), null, false,
            false, null, false, false, false, null);
    }

    private static LastScanDto finishedAt(Instant when) {
        return new LastScanDto(when.minusSeconds(60), when, "nightly", 10, 0, 0, null);
    }

    @Test
    void aFailedLastScanReportsItsErrorAndFinishTime() {
        LastScanDto failed = new LastScanDto(FINISHED.minusSeconds(60), FINISHED, "nightly", 0, 0, 0,
            "Library folder is not readable");
        stubEverything(List.of(), 0, 0, scan(12, failed));

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

    private static FullNeedListDto fullNeed(int unresolved) {
        return new FullNeedListDto(List.of(), new FullNeedCountsDto(9, 8, 7, 6, unresolved, 5, 4, 3));
    }

    private static NeedListDto forcedNeed(int unresolved) {
        return new NeedListDto(List.of(), new gregochr.cinefilm.subtitle.ForcedNeedDtos.CountsDto(9, 8, 7, 6, 5,
            unresolved, 4));
    }

    private void stubEverything(List<AttentionItem> items, int full, int forced, ScanStatusDto scan) {
        when(admin.attention()).thenReturn(new Attention(items));
        when(admin.status()).thenReturn(scan);
        when(fullNeed.list()).thenReturn(fullNeed(full));
        when(forcedNeed.list()).thenReturn(forcedNeed(forced));
        when(sessions.sessions()).thenReturn(List.of());
        when(health.playbackCounts()).thenReturn(new PlaybackCounts(0, 0));
        when(health.playingNow()).thenReturn(List.of());
    }

    @Test
    void everyFieldIsReadFromItsOwnSource() {
        PlayingNow playing = new PlayingNow("3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b", 11, "Blade Runner", "Mac", true,
            "home", "720p");
        when(admin.attention()).thenReturn(new Attention(List.of(item("needs-match"), item("needs-match"),
            item("needs-match"), item("multiple-files"), item("parts-mismatch"))));
        when(admin.status()).thenReturn(scan(321, finishedAt(FINISHED)));
        when(fullNeed.list()).thenReturn(fullNeed(7));
        when(forcedNeed.list()).thenReturn(forcedNeed(11));
        when(sessions.sessions()).thenReturn(List.of(
            new SessionRow("a", "Film", 1, "720p", 1.0, 2, NOW, false),
            new SessionRow("b", "Film", 2, "480p", 1.0, 2, NOW, false)));
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
    void severalFilesCountsPartsMismatchAndMultipleFilesAndNothingElse() {
        stubEverything(List.of(item("protected"), item("needs-conversion"), item("unreadable"),
            item("multiple-files"), item("parts-mismatch"), item("parts-mismatch")), 0, 0, scan(1, null));

        AdminSummary summary = service.summary();

        assertThat(summary.todo().severalFiles()).isEqualTo(3);
        assertThat(summary.todo().pickFilm()).isZero();
    }

    @Test
    void pickFilmCountsOnlyNeedsMatch() {
        stubEverything(List.of(item("needs-match"), item("multiple-files"), item("protected")), 0, 0, scan(1, null));

        assertThat(service.summary().todo().pickFilm()).isEqualTo(1);
    }

    @Test
    void subtitlesUnresolvedIsTheSumOfBothTabs() {
        stubEverything(List.of(), 4, 9, scan(1, null));

        AdminSummary summary = service.summary();

        assertThat(summary.todo().fullMissing()).isEqualTo(4);
        assertThat(summary.todo().forcedQuestion()).isEqualTo(9);
        assertThat(summary.subtitlesUnresolved()).isEqualTo(13);
    }

    @Test
    void anEmptyLibraryThatWasNeverScannedHasNoScanTime() {
        stubEverything(List.of(), 0, 0, scan(0, null));

        AdminSummary summary = service.summary();

        assertThat(summary.library().films()).isZero();
        assertThat(summary.library().lastScanFinishedAt()).isNull();
        assertThat(summary.library().lastScanError()).isNull();
        assertThat(summary.live()).isZero();
        assertThat(summary.playingNow()).isEmpty();
    }
}
