package gregochr.cinefilm.summary;

import gregochr.cinefilm.health.PlaybackHealthService;
import gregochr.cinefilm.library.AdminDtos.LastScanDto;
import gregochr.cinefilm.library.AdminService;
import gregochr.cinefilm.library.AdminService.SummaryFigures;
import gregochr.cinefilm.subtitle.ForcedNeedService;
import gregochr.cinefilm.subtitle.FullNeedService;
import gregochr.cinefilm.summary.AdminSummaryDtos.AdminSummary;
import gregochr.cinefilm.summary.AdminSummaryDtos.Library;
import gregochr.cinefilm.summary.AdminSummaryDtos.Todo;
import gregochr.cinefilm.transcode.SessionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers the figures of Admin's rail and To do strip from the services that already compute them, so no rule is
 * written twice. Nothing is cached: the client reads it again when something changes.
 *
 * <p>Lives in its own package, depending on {@code library}, {@code subtitle}, {@code transcode} and {@code health}
 * and used by none of them, so it adds no package cycle ({@code library} and {@code subtitle} already import each
 * other).
 */
@Service
public class AdminSummaryService {

    private final AdminService admin;
    private final FullNeedService fullNeed;
    private final ForcedNeedService forcedNeed;
    private final SessionManager sessions;
    private final PlaybackHealthService health;

    /** Creates the service over the services whose figures it gathers. */
    public AdminSummaryService(AdminService admin, FullNeedService fullNeed, ForcedNeedService forcedNeed,
                               SessionManager sessions, PlaybackHealthService health) {
        this.admin = admin;
        this.fullNeed = fullNeed;
        this.forcedNeed = forcedNeed;
        this.sessions = sessions;
        this.health = health;
    }

    /**
     * The summary, read fresh, in one read-only transaction and one database snapshot ({@code REPEATABLE_READ}: on
     * PostgreSQL a single snapshot for the whole transaction, not one per statement), so the counts agree with each
     * other. The live sessions and the scan-running flags are in memory, outside the snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminSummary summary() {
        SummaryFigures library = admin.summaryFigures();
        long fullMissing = fullNeed.unresolvedCount();
        long forcedQuestion = forcedNeed.unresolvedCount();
        LastScanDto last = library.lastScan();
        return new AdminSummary(new Todo(library.needsMatch(), library.severalFiles(), fullMissing, forcedQuestion),
            fullMissing + forcedQuestion, sessions.sessions().size(),
            new Library(library.browsableFilms(), last == null ? null : last.finishedAt(),
                last == null ? null : last.error()),
            health.playbackCounts(), health.playingNow());
    }
}
