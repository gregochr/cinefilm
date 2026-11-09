package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** The ratings columns round-trip through Hibernate on PostgreSQL, and the due-films queries order as promised. */
@Transactional
class FilmRatingsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    private Film saved(String title, Instant fetchedAt) {
        Film film = film(null, title, 2000, NOW);
        film.setRatingsFetchedAt(fetchedAt);
        return films.saveAndFlush(film);
    }

    @Test
    void theRatingsRoundTrip() {
        Film film = film(null, "Rated Round Trip", 1995, NOW);
        film.setImdbId("tt0113277");
        film.setImdbRating(new BigDecimal("8.3"));
        film.setImdbVotes(712345);
        film.setRtScore(84);
        film.setRtUrl("https://www.rottentomatoes.com/m/heat_1995");
        film.setRatingsFetchedAt(NOW);
        Long id = films.saveAndFlush(film).getId();

        Film loaded = films.findById(id).orElseThrow();

        assertThat(loaded.getImdbId()).isEqualTo("tt0113277");
        assertThat(loaded.getImdbRating()).isEqualByComparingTo("8.3");
        assertThat(loaded.getImdbVotes()).isEqualTo(712345);
        assertThat(loaded.getRtScore()).isEqualTo(84);
        assertThat(loaded.getRtUrl()).isEqualTo("https://www.rottentomatoes.com/m/heat_1995");
        assertThat(loaded.getRatingsFetchedAt()).isEqualTo(NOW);
    }

    @Test
    void dueFilmsComeNeverFetchedFirstThenOldestAndFreshOnesAreLeftOut() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        Film never = saved("Never", null);
        Film oldest = saved("Oldest", NOW.minus(Duration.ofDays(90)));
        Film stale = saved("Stale", NOW.minus(Duration.ofDays(31)));
        Film atCutoff = saved("At cutoff", cutoff);
        saved("Fresh", NOW.minus(Duration.ofDays(1)));

        List<Long> due = films.findIdsWithRatingsDueBefore(cutoff);

        List<Long> mine = due.stream()
            .filter(List.of(never.getId(), oldest.getId(), stale.getId(), atCutoff.getId())::contains).toList();
        assertThat(mine).containsExactly(never.getId(), oldest.getId(), stale.getId());
        assertThat(due.indexOf(never.getId())).isLessThan(due.indexOf(oldest.getId()));
    }

    @Test
    void everyFilmOldestFirstPutsTheNeverFetchedFirst() {
        Film fresh = saved("Fresh", NOW.minus(Duration.ofDays(1)));
        Film never = saved("Never", null);
        Film old = saved("Old", NOW.minus(Duration.ofDays(40)));

        List<Long> all = films.findIdsByRatingsOldestFirst();

        assertThat(all.indexOf(never.getId())).isLessThan(all.indexOf(old.getId()));
        assertThat(all.indexOf(old.getId())).isLessThan(all.indexOf(fresh.getId()));
    }

    @Test
    void theLatestFetchIsTheMaximum() {
        saved("A", NOW.minus(Duration.ofDays(5)));
        saved("B", NOW.minus(Duration.ofDays(2)));
        saved("C", null);

        assertThat(films.findLatestRatingsFetchedAt()).isAfterOrEqualTo(NOW.minus(Duration.ofDays(2)));
    }
}
