package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.film.ChristmasChoice;
import gregochr.cinefilm.film.ChristmasOverrides;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V34 on PostgreSQL 17: the table starts empty, holds one row per film and only INCLUDE or EXCLUDE. */
class ChristmasOverrideIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChristmasOverrides overrides;

    @Test
    void theTableHasItsColumnsAndStartsEmpty() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'christmas_override' ORDER BY column_name", String.class);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM christmas_override", Integer.class);

        assertThat(columns).containsExactly("choice:character varying:NO",
            "created_at:timestamp with time zone:NO", "tmdb_id:bigint:NO");
        assertThat(rows).isZero();
    }

    @Test
    void theCheckConstraintRejectsAnyOtherChoice() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO christmas_override (tmdb_id, choice, created_at) VALUES (1, 'MAYBE', now())"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aFilmCannotHaveTwoRows() {
        jdbc.update("INSERT INTO christmas_override (tmdb_id, choice, created_at) VALUES (2, 'INCLUDE', now())");

        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO christmas_override (tmdb_id, choice, created_at) VALUES (2, 'EXCLUDE', now())"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM christmas_override WHERE tmdb_id = 2");
    }

    @Test
    void theServiceReadsWritesFlipsAndDeletesThroughTheTable() {
        overrides.set(3L, ChristmasChoice.INCLUDE);
        overrides.set(4L, ChristmasChoice.EXCLUDE);
        assertThat(overrides.snapshot().include()).containsExactly(3L);
        assertThat(overrides.snapshot().exclude()).containsExactly(4L);

        overrides.set(3L, ChristmasChoice.EXCLUDE);
        assertThat(overrides.snapshot().include()).isEmpty();
        assertThat(overrides.snapshot().exclude()).containsExactlyInAnyOrder(3L, 4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM christmas_override WHERE tmdb_id = 3", Integer.class))
            .isEqualTo(1);

        overrides.remove(3L);
        overrides.remove(4L);
        overrides.remove(999L);
        assertThat(overrides.snapshot().exclude()).isEmpty();
    }
}
