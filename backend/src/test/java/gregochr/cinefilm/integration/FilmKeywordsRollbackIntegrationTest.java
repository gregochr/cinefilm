package gregochr.cinefilm.integration;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.film;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmKeywords;
import gregochr.cinefilm.film.FilmRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A keywords refetch that fails part-way (after the old rows were deleted) rolls everything back on PostgreSQL: the
 * film keeps its old keywords and its old stamp. Not {@code @Transactional}: the save's own transaction is the subject.
 */
class FilmKeywordsRollbackIntegrationTest extends IntegrationTestBase {

    @Autowired
    private FilmRepository films;

    @Autowired
    private FilmKeywords keywords;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aSaveThatThrowsAfterTheDeleteLeavesTheOldKeywordsAndTheOldStamp() {
        Instant old = NOW.minus(Duration.ofDays(60));
        Film film = film(null, "Keywords Rollback", 1988, NOW);
        film.setTmdbId(772_001L);
        film.setKeywordsFetchedAt(old);
        film.getKeywords().add("christmas");
        Long id = films.saveAndFlush(film).getId();
        try {
            // A null entry throws while the rows are being re-inserted, after the stamp and the delete ran.
            assertThatThrownBy(() -> keywords.save(id, 772_001L, Arrays.asList("snow", null), NOW))
                .isInstanceOf(RuntimeException.class);

            assertThat(jdbc.queryForList("select keyword from film_keyword where film_id = ?", String.class, id))
                .isEqualTo(List.of("christmas"));
            assertThat(films.findById(id).orElseThrow().getKeywordsFetchedAt()).isEqualTo(old);
        } finally {
            films.deleteById(id);
        }
    }
}
