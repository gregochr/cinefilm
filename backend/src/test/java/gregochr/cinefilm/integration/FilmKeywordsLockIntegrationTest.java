package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmKeywords;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.util.List;
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
 * The keywords backfill against a rematch that holds the film's row, on two real transactions on PostgreSQL. The
 * rematch flushes the new TMDB id (and so holds the row lock); a save for the old id on another thread must wait for
 * that lock, and once the rematch has committed its guarded stamp matches nothing: it returns false and leaves no
 * keyword rows. A check made by a plain read before the lock would see the old id and write the old film's keywords.
 * Latches with bounded waits order the threads; nothing sleeps. Not transactional itself.
 */
class FilmKeywordsLockIntegrationTest extends IntegrationTestBase {

    private static final long WAIT_SECONDS = 30;

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmKeywords keywords;

    @Autowired
    private LibraryIngest ingest;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private boolean saveIsWaitingOnALock() {
        // The save's guarded stamp is the statement that blocks; the probe's own backend is excluded so its query
        // text (which names the column) can never match itself.
        Integer waiting = jdbc.queryForObject(
            "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and pid <> pg_backend_pid() "
                + "and datname = current_database() and query like '%keywords_fetched_at%'",
            Integer.class);
        return waiting != null && waiting > 0;
    }

    @Test
    void aSaveForTheOldIdThatWaitsOnTheRematchesRowLockIsRefusedOnceTheRematchCommits() throws Exception {
        Film seed = film(null, "Keywords Locked", 1995, NOW);
        seed.setTmdbId(882_001L);
        Long id = films.saveAndFlush(seed).getId();
        MovieDetails newFilm = new MovieDetails(882_002L, "Keywords Locked", "Keywords Locked", 1995, null, 100,
            List.of(), null, null, 0, List.of(), List.of(), null, null, null, List.of());
        CountDownLatch rematchHoldsRow = new CountDownLatch(1);
        CountDownLatch saveStarting = new CountDownLatch(1);
        ExecutorService backfill = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> save = backfill.submit(() -> {
                assertThat(rematchHoldsRow.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                saveStarting.countDown();
                return keywords.save(id, 882_001L, List.of("christmas"), NOW);
            });

            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                Film loaded = films.findById(id).orElseThrow();
                // applyMovie flushes the new TMDB id and writes the row, so this transaction now holds the row lock.
                ingest.applyMovie(loaded, newFilm);
                rematchHoldsRow.countDown();
                try {
                    assertThat(saveStarting.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                // Wait, spinning on the database's own view, until the save is blocked on this transaction's row
                // lock. Asserted before this transaction commits: a timing miss fails the test instead of passing
                // without the race ever having happened.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
                boolean reached = saveIsWaitingOnALock();
                while (!reached && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                    reached = saveIsWaitingOnALock();
                }
                assertThat(reached).as("the save waited on the rematch's row lock").isTrue();
            });

            assertThat(save.get(WAIT_SECONDS, TimeUnit.SECONDS)).as("a save for the old id after the rematch")
                .isFalse();
        } finally {
            backfill.shutdownNow();
        }

        Film result = films.findById(id).orElseThrow();
        assertThat(result.getTmdbId()).isEqualTo(882_002L);
        assertThat(result.getKeywordsFetchedAt()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from film_keyword where film_id = ?", Integer.class, id))
            .isZero();
    }
}
