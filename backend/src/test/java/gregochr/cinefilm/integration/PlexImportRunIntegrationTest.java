package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.plex.PlexImportRun;
import gregochr.cinefilm.plex.PlexImportRunRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V4 on real PostgreSQL: the report is a TEXT column, so a report far longer than a VARCHAR(255) round-trips. */
class PlexImportRunIntegrationTest extends IntegrationTestBase {

    @Autowired
    private PlexImportRunRepository runs;

    @Test
    void aLongReportAndAnErrorRoundTrip() {
        PlexImportRun run = new PlexImportRun();
        run.setStartedAt(Instant.parse("2026-11-08T14:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-11-08T14:09:30Z"));
        run.setReport("x".repeat(100_000));
        run.setError("Plex is not reachable");

        Long id = runs.saveAndFlush(run).getId();

        PlexImportRun stored = runs.findById(id).orElseThrow();
        assertThat(stored.getReport()).hasSize(100_000);
        assertThat(stored.getError()).isEqualTo("Plex is not reachable");
        assertThat(runs.findFirstByFinishedAtNotNullOrderByIdDesc()).get().extracting(PlexImportRun::getId)
            .isEqualTo(id);
    }
}
