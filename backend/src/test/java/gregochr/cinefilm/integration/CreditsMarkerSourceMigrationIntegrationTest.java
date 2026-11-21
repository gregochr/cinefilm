package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The credits marker source column (V35) on PostgreSQL: a database migrated only as far as V34 holds an edition with a
 * marker, then V35 is applied. It runs in a schema of its own, beside the one the application context migrated.
 */
class CreditsMarkerSourceMigrationIntegrationTest extends IntegrationTestBase {

    private static final String SCHEMA = "marker_source_test";

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

    private static void insertEdition(Connection connection, Long markerMs, String source, boolean withSource)
        throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO film (title, sort_title, release_year, added_at) "
                + "VALUES ('Heat', 'heat', 1995, now())");
        }
        String columns = withSource ? ", credits_marker_source" : "";
        String values = withSource ? ", ?" : "";
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO edition (film_id, name, runtime_ms, credits_marker_ms" + columns
                + ") VALUES ((SELECT max(id) FROM film), 'Theatrical Cut', 10200000, ?" + values + ")")) {
            statement.setObject(1, markerMs);
            if (withSource) {
                statement.setString(2, source);
            }
            statement.executeUpdate();
        }
    }

    @AfterEach
    void dropTheSchema() throws SQLException {
        try (Connection connection = connection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void aMarkerStoredBeforeTheColumnKeepsItsMarkerAndGetsANullSource() throws Exception {
        flyway("34").migrate();
        try (Connection connection = connection()) {
            insertEdition(connection, 9_600_000L, null, false);
        }

        flyway("latest").migrate();

        try (Connection connection = connection();
             ResultSet found = connection.createStatement()
                 .executeQuery("SELECT credits_marker_ms, credits_marker_source FROM edition")) {
            assertThat(found.next()).isTrue();
            assertThat(found.getLong("credits_marker_ms")).isEqualTo(9_600_000L);
            assertThat(found.getString("credits_marker_source")).isNull();
            assertThat(found.next()).isFalse();
        }
    }

    @Test
    void theColumnAcceptsTheThreeSourcesAndRefusesAnythingElse() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = connection()) {
            for (String source : new String[] {"AUTO", "PLEX", "OWNER"}) {
                insertEdition(connection, 9_600_000L, source, true);
            }

            for (String refused : new String[] {"BOGUS", "plex"}) {
                assertThatThrownBy(() -> insertEdition(connection, 9_600_000L, refused, true))
                    .isInstanceOf(SQLException.class).hasMessageContaining("edition_credits_marker_source_check");
            }
            try (ResultSet count = connection.createStatement().executeQuery("SELECT count(*) FROM edition")) {
                assertThat(count.next()).isTrue();
                assertThat(count.getInt(1)).as("only the three accepted rows were stored").isEqualTo(3);
            }
        }
    }
}
