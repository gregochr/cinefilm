package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The subtitle frame rate columns (V39) and their backfill (V40) on PostgreSQL: a database migrated only as far as V38
 * holds downloads and references, then V39 and V40 are applied. It runs in a schema of its own, beside the one the
 * application context migrated.
 */
class SubtitleFrameRatesMigrationIntegrationTest extends IntegrationTestBase {

    private static final String SCHEMA = "frame_rates_test";

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

    @AfterEach
    void dropTheSchema() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** One film (id 1) with a part file, and its search results, before the migrations under test. */
    private static void filmWithDownloads(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO film (title, sort_title, release_year, added_at, imdb_id, os_candidates, "
            + "os_best) VALUES ('Heat', 'heat', 1995, now(), 'tt0113277', "
            + "'[{\"id\":1,\"fileId\":101,\"fps\":23.976},{\"id\":2,\"fileId\":102,\"fps\":25.0}]', "
            + "'{\"id\":3,\"fileId\":103,\"fps\":24.0}')");
        execute(connection, "INSERT INTO edition (film_id, name, runtime_ms) "
            + "VALUES ((SELECT max(id) FROM film), 'Theatrical Cut', 10200000)");
        execute(connection, "INSERT INTO media_file (edition_id, path, kind, added_at) "
            + "VALUES ((SELECT max(id) FROM edition), 'Heat.m4v', 'ORIGINAL', now())");
        String sidecar = "INSERT INTO subtitle_sidecar (path, size_bytes, modified_at, presence, media_file_id, "
            + "whole_edition, owner_attached, cues_text, parse_report, cue_count, last_end_ms, offset_ms, updated_at, "
            + "origin, kind, opensubtitles_file_id, offset_source) VALUES (?, 1, now(), 'PRESENT', "
            + "(SELECT max(id) FROM media_file), true, false, '', '', 0, 0, 0, now(), 'OPENSUBTITLES', 'FORCED', ?, ?)";
        insertSidecar(connection, sidecar, "opensubtitles:1:101", 101, null);
        insertSidecar(connection, sidecar, "opensubtitles:1:102", 102, "OWNER");
        insertSidecar(connection, sidecar, "opensubtitles:1:999", 999, null);
        execute(connection, "INSERT INTO opensubtitles_reference (film_id, imdb_id, os_file_id, cues_text, cue_count, "
            + "fetched_at) VALUES ((SELECT max(id) FROM film), 'tt0113277', 103, '', 0, now())");
    }

    private static void insertSidecar(Connection connection, String sql, String path, long fileId, String offsetSource)
        throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, path);
            statement.setLong(2, fileId);
            statement.setString(3, offsetSource);
            statement.executeUpdate();
        }
    }

    @Test
    void theBackfillFillsTheRatesAndLeavesRetimeUndecidedEvenForAnOwnerTimedDownload() throws Exception {
        flyway("38").migrate();
        try (Connection connection = connection()) {
            filmWithDownloads(connection);
        }

        flyway("latest").migrate();

        try (Connection connection = connection();
             ResultSet found = connection.createStatement().executeQuery(
                 "SELECT opensubtitles_file_id, timed_fps, retime FROM subtitle_sidecar "
                     + "ORDER BY opensubtitles_file_id")) {
            assertThat(found.next()).isTrue();
            assertThat(found.getDouble("timed_fps")).isEqualTo(23.976);
            assertThat(found.getString("retime")).isNull();
            assertThat(found.next()).isTrue();
            assertThat(found.getDouble("timed_fps")).isEqualTo(25.0);
            assertThat(found.getString("retime")).as("owner-timed, still undecided").isNull();
            assertThat(found.next()).isTrue();
            assertThat(found.getObject("timed_fps")).as("no result for file 999").isNull();
            assertThat(found.getString("retime")).isNull();
            assertThat(found.next()).isFalse();
        }
        try (Connection connection = connection();
             ResultSet found = connection.createStatement().executeQuery(
                 "SELECT timed_fps, cues_version FROM opensubtitles_reference")) {
            assertThat(found.next()).isTrue();
            assertThat(found.getDouble("timed_fps")).isEqualTo(24.0);
            assertThat(found.getInt("cues_version")).isZero();
            assertThat(found.next()).isFalse();
        }
    }

    @Test
    void theRetimeColumnAcceptsTheThreeValuesAndRefusesAnythingElse() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = connection()) {
            filmWithDownloads(connection);
            for (String value : new String[] {"AUTO", "OFF", "OFF_FITS"}) {
                execute(connection, "UPDATE subtitle_sidecar SET retime = '" + value + "' WHERE path = "
                    + "'opensubtitles:1:101'");
            }

            assertThatThrownBy(() -> execute(connection, "UPDATE subtitle_sidecar SET retime = 'BOGUS'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("subtitle_sidecar_retime_check");
        }
    }
}
