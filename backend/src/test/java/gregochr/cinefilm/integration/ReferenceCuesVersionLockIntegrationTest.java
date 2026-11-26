package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reference row lock that closes the race between a check's save and the retime job's rewrite, on two real
 * transactions on PostgreSQL. A check's save reads the reference's cue version with {@code FOR UPDATE} and writes its
 * verdict in the same transaction; a rewrite's update of that row, on another thread, must wait for the save to commit.
 * Latches with bounded waits order the threads; nothing sleeps. Not transactional itself.
 */
class ReferenceCuesVersionLockIntegrationTest extends IntegrationTestBase {

    private static final long WAIT_SECONDS = 30;

    @Autowired
    private OpenSubtitlesReferenceRepository references;

    @Autowired
    private gregochr.cinefilm.film.FilmRepository films;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private boolean updateIsWaitingOnALock() {
        // PostgreSQL keeps the first pg_stat_activity read for the whole transaction: clear it to read live status.
        jdbc.queryForObject("select pg_stat_clear_snapshot()::text", String.class);
        Integer waiting = jdbc.queryForObject(
            "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and pid <> pg_backend_pid() "
                + "and pg_backend_pid() = any(pg_blocking_pids(pid)) "
                + "and datname = current_database() and query like '%set cues_version%'",
            Integer.class);
        return waiting != null && waiting > 0;
    }

    @Test
    void aRewriteOfTheReferenceWaitsForACheckSaveHoldingItsRowAndThenGoesOn() throws Exception {
        Long filmId = films.saveAndFlush(film(null, "Locked Reference", 1995, NOW)).getId();
        OpenSubtitlesReference seed = new OpenSubtitlesReference();
        seed.setFilmId(filmId);
        seed.setImdbId("tt0000001");
        seed.setOsFileId(5011);
        seed.setCuesText("1\n00:00:01,000 --> 00:00:02,000\nHi\n\n");
        seed.setCueCount(1);
        seed.setFetchedAt(NOW);
        Long id = references.saveAndFlush(seed).getId();
        CountDownLatch saveHoldsRow = new CountDownLatch(1);
        ExecutorService rewrite = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> update = rewrite.submit(() -> {
                assertThat(saveHoldsRow.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return new TransactionTemplate(transactions).execute(status -> jdbc.update(
                    "update opensubtitles_reference set cues_version = cues_version + 1 where id = ?", id));
            });

            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(references.findCuesVersionForUpdate(id)).contains(0);
                saveHoldsRow.countDown();
                // Spin on the database's own view until the rewrite's update is blocked on this transaction's row
                // lock: a timing miss fails the test instead of passing without the race having happened.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                boolean reached = updateIsWaitingOnALock();
                while (!reached && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                    reached = updateIsWaitingOnALock();
                }
                assertThat(reached).as("the rewrite waited on the save's row lock").isTrue();
            });

            assertThat(update.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            rewrite.shutdownNow();
        }

        assertThat(references.findById(id).orElseThrow().getCuesVersion()).isEqualTo(1);
    }
}
