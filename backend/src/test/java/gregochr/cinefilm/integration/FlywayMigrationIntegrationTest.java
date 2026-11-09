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
            "film_preference", "scan_run", "plex_import_run");
    }

    @Test
    void instantsAreStoredWithTimeZone() {
        String type = jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns WHERE table_name = 'film' AND column_name = 'added_at'",
            String.class);
        assertThat(type).isEqualTo("timestamp with time zone");
    }
}
