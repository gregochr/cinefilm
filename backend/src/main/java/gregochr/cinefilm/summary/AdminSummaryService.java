package gregochr.cinefilm.summary;

import gregochr.cinefilm.health.PlaybackHealthService;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import gregochr.cinefilm.library.AdminService;
import gregochr.cinefilm.subtitle.ForcedNeedService;
import gregochr.cinefilm.subtitle.FullNeedService;
import gregochr.cinefilm.summary.AdminSummaryDtos.AdminSummary;
import gregochr.cinefilm.summary.AdminSummaryDtos.Library;
import gregochr.cinefilm.summary.AdminSummaryDtos.Todo;
import gregochr.cinefilm.transcode.SessionManager;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

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

    private static final String NEEDS_MATCH = "needs-match";
    private static final Set<String> SEVERAL_FILES = Set.of("multiple-files", "parts-mismatch");

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

    /** The summary, read fresh. */
    public AdminSummary summary() {
        List<AttentionItem> items = admin.attention().items();
        long pickFilm = items.stream().filter(item -> NEEDS_MATCH.equals(item.kind())).count();
        long severalFiles = items.stream().filter(item -> SEVERAL_FILES.contains(item.kind())).count();
        long fullMissing = fullNeed.list().counts().unresolved();
        long forcedQuestion = forcedNeed.list().counts().unresolved();
        ScanStatusDto scan = admin.status();
        return new AdminSummary(new Todo(pickFilm, severalFiles, fullMissing, forcedQuestion),
            fullMissing + forcedQuestion, sessions.sessions().size(),
            new Library(scan.counts().films(), scan.lastScan() == null ? null : scan.lastScan().finishedAt()),
            health.playbackCounts(), health.playingNow());
    }
}
