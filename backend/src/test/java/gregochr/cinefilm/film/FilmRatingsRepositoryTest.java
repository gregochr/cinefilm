package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The ratings columns and the due-films queries, on the in-memory database. */
class FilmRatingsRepositoryTest extends ApiTestBase {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));

    @Autowired
    private EntityManager entityManager;

    private Film saved(String title, Instant fetchedAt) {
        Film film = film(null, title, 2000, NOW);
        film.setRatingsFetchedAt(fetchedAt);
        return films.saveAndFlush(film);
    }

    @Test
    void theRatingsRoundTrip() {
        Film film = film(null, "Heat", 1995, NOW);
        film.setImdbId("tt0113277");
        film.setImdbRating(new BigDecimal("8.3"));
        film.setImdbVotes(712345);
        film.setRtScore(84);
        film.setRtUrl("https://www.rottentomatoes.com/m/heat_1995");
        film.setRatingsFetchedAt(NOW);
        Long id = films.saveAndFlush(film).getId();
        // The test is transactional: without clearing, findById would hand back this same instance, not the row.
        entityManager.clear();

        Film loaded = films.findById(id).orElseThrow();

        assertThat(loaded.getImdbId()).isEqualTo("tt0113277");
        assertThat(loaded.getImdbRating()).isEqualByComparingTo("8.3");
        assertThat(loaded.getImdbVotes()).isEqualTo(712345);
        assertThat(loaded.getRtScore()).isEqualTo(84);
        assertThat(loaded.getRtUrl()).isEqualTo("https://www.rottentomatoes.com/m/heat_1995");
        assertThat(loaded.getRatingsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void dueFilmsComeNeverFetchedFirstThenOldestAndOnlyOlderThanTheCutoff() {
        Film stale = saved("Stale", CUTOFF.minusMillis(1));
        Film oldest = saved("Oldest", NOW.minus(Duration.ofDays(90)));
        Film never = saved("Never", null);
        saved("Exactly at the cutoff", CUTOFF);
        saved("Fresh", NOW.minus(Duration.ofDays(1)));
        Film neverToo = saved("Never too", null);

        assertThat(films.findIdsWithRatingsDueBefore(CUTOFF))
            .containsExactly(never.getId(), neverToo.getId(), oldest.getId(), stale.getId());
    }

    @Test
    void everyFilmIsListedNeverFetchedFirstThenOldestFirst() {
        Film fresh = saved("Fresh", NOW.minus(Duration.ofDays(1)));
        Film never = saved("Never", null);
        Film old = saved("Old", NOW.minus(Duration.ofDays(40)));

        assertThat(films.findIdsByRatingsOldestFirst()).containsExactly(never.getId(), old.getId(), fresh.getId());
    }

    @Test
    void theLatestFetchIsTheMostRecentStampAndNullWhenNoneIsStamped() {
        saved("Never", null);
        assertThat(films.findLatestRatingsFetchedAt()).isNull();

        saved("A", NOW.minus(Duration.ofDays(5)));
        saved("B", NOW.minus(Duration.ofDays(2)));

        assertThat(films.findLatestRatingsFetchedAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
    }
}
