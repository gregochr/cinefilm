package gregochr.cinefilm.integration;

import static gregochr.cinefilm.health.HealthSamples.EXAMPLE;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The figure columns' migration (V26) and the Java backfill (V27) on PostgreSQL: a database migrated only as far as
 * V25 holds rows of the old shape, then the rest is applied. It runs in a schema of its own, beside the one the
 * application context migrated.
 */
class PlaybackHealthBackfillIntegrationTest extends IntegrationTestBase {

    private static final String SCHEMA = "backfill_test";

    private Flyway flyway(String target) {
        return Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .target(target)
            .load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl() + "&currentSchema=" + SCHEMA,
            POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void insert(Connection connection, String playbackId, String summary) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO playback_health (playback_id, edition_id, film_id, film_title, started_at, updated_at, seq, "
                + "summary) VALUES (?, 1, 1, 'Blade Runner', now(), now(), 1, ?)")) {
            statement.setString(1, playbackId);
            statement.setString(2, summary);
            statement.executeUpdate();
        }
    }

    private static ResultSet figures(Connection connection, String playbackId) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
            "SELECT watched_ms, stall_count, stall_ms, startup_ms, has_problem, environment FROM playback_health "
                + "WHERE playback_id = ?");
        statement.setString(1, playbackId);
        ResultSet found = statement.executeQuery();
        assertThat(found.next()).isTrue();
        return found;
    }

    @AfterEach
    void dropTheSchema() throws SQLException {
        try (Connection connection = connection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void rowsStoredBeforeTheColumnsAreBackfilledFromTheirSummaryAndAnUnreadableOneKeepsTheDefaults()
        throws Exception {
        flyway("25").migrate();
        try (Connection connection = connection()) {
            insert(connection, "00000000-0000-4000-8000-000000000001", EXAMPLE);
            insert(connection, "00000000-0000-4000-8000-000000000002", "not json");
        }

        flyway("latest").migrate();

        try (Connection connection = connection()) {
            ResultSet read = figures(connection, "00000000-0000-4000-8000-000000000001");
            assertThat(read.getLong("watched_ms")).isEqualTo(171_000);
            assertThat(read.getLong("stall_count")).isEqualTo(2);
            assertThat(read.getLong("stall_ms")).isEqualTo(9_400);
            assertThat(read.getLong("startup_ms")).isEqualTo(2_380);
            assertThat(read.getBoolean("has_problem")).isTrue();
            assertThat(read.getString("environment")).isEqualTo("train");

            ResultSet unread = figures(connection, "00000000-0000-4000-8000-000000000002");
            assertThat(unread.getLong("watched_ms")).isZero();
            assertThat(unread.getLong("stall_count")).isZero();
            unread.getLong("startup_ms");
            assertThat(unread.wasNull()).isTrue();
            assertThat(unread.getBoolean("has_problem")).isFalse();
            assertThat(unread.getString("environment")).isNull();
        }
    }
}
