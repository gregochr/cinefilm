package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Every migration applies to an empty PostgreSQL 17, and Hibernate's schema validation passes against it. */
class FlywayMigrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment env;

    @Test
    void runsOnPostgresSeventeen() {
        String version = jdbc.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("17");
    }

    @Test
    void allMigrationsAppliedSuccessfully() {
        List<Boolean> outcomes = jdbc.queryForList("SELECT success FROM flyway_schema_history", Boolean.class);
        assertThat(outcomes).isNotEmpty().containsOnly(true);
    }

    @Test
    void hibernateValidatedTheSchemaAtStartup() {
        // The context only starts if validation passed; assert the setting so it cannot be silently relaxed.
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void everyTableExists() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("film", "film_genre", "film_collection", "collection_part", "edition",
            "media_file", "audio_stream", "subtitle_stream", "person", "credit", "playback_state",
            "film_preference", "scan_run", "plex_import_run", "media_keyframes", "server_setting");
    }

    @Test
    void theRatingsColumnsExistWithTheirTypesAndAreNullable() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'film' AND column_name IN ('imdb_id', 'imdb_rating', 'imdb_votes', "
                + "'rt_score', 'rt_url', 'ratings_fetched_at') ORDER BY column_name", String.class);
        assertThat(columns).containsExactly("imdb_id:character varying:YES", "imdb_rating:numeric:YES",
            "imdb_votes:integer:YES", "ratings_fetched_at:timestamp with time zone:YES", "rt_score:integer:YES",
            "rt_url:character varying:YES");
        Integer scale = jdbc.queryForObject("SELECT numeric_scale FROM information_schema.columns "
            + "WHERE table_name = 'film' AND column_name = 'imdb_rating'", Integer.class);
        assertThat(scale).isEqualTo(1);
    }

    @Test
    void theRatingsBudgetTableHoldsOneNotNullCountPerDay() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'ratings_budget' ORDER BY column_name", String.class);
        assertThat(columns).containsExactly("budget_day:date:NO", "used:integer:NO");
        List<String> keys = jdbc.queryForList(
            "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name "
                + "WHERE tc.table_name = 'ratings_budget' AND tc.constraint_type = 'PRIMARY KEY'", String.class);
        assertThat(keys).containsExactly("budget_day");
    }

    @Test
    void theVideoDurationColumnIsAnExpandOnlyNullableBigintSoExistingRowsStayValid() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name || ':' || data_type || ':' || is_nullable || ':' || coalesce(column_default, '') "
                + "FROM information_schema.columns WHERE table_name = 'media_file' "
                + "AND column_name = 'video_duration_ms'", String.class);
        assertThat(columns).containsExactly("video_duration_ms:bigint:YES:");
    }

    @Test
    void instantsAreStoredWithTimeZone() {
        String type = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns WHERE table_name = 'film' AND column_name = 'added_at'",
            String.class);
        assertThat(type).isEqualTo("timestamp with time zone");
    }
}
