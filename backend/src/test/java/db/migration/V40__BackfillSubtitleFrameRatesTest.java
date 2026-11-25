package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The backfill's row logic, against in-memory H2 tables with only the columns it reads (no Spring, no Flyway). */
class V40__BackfillSubtitleFrameRatesTest {

    private Connection connection;
    private Context context;

    @BeforeEach
    void tables() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE film (id BIGINT PRIMARY KEY, os_candidates TEXT, "
                + "os_full_candidates TEXT, os_best VARCHAR(2000))");
            statement.execute("CREATE TABLE edition (id BIGINT PRIMARY KEY, film_id BIGINT NOT NULL)");
            statement.execute("CREATE TABLE media_file (id BIGINT PRIMARY KEY, edition_id BIGINT NOT NULL)");
            statement.execute("CREATE TABLE subtitle_sidecar (id BIGINT PRIMARY KEY, media_file_id BIGINT, "
                + "origin VARCHAR(20) NOT NULL, opensubtitles_file_id BIGINT, timed_fps DOUBLE PRECISION, "
                + "retime VARCHAR(10), offset_source VARCHAR(10), owner_verified_at TIMESTAMP WITH TIME ZONE)");
            statement.execute("CREATE TABLE opensubtitles_reference (id BIGINT PRIMARY KEY, film_id BIGINT NOT NULL, "
                + "os_file_id BIGINT NOT NULL, timed_fps DOUBLE PRECISION)");
            statement.execute("INSERT INTO edition VALUES (1, 1)");
            statement.execute("INSERT INTO media_file VALUES (1, 1)");
        }
        context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    private void film(String candidates, String full, String best) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO film VALUES (1, ?, ?, ?)")) {
            statement.setString(1, candidates);
            statement.setString(2, full);
            statement.setString(3, best);
            statement.executeUpdate();
        }
    }

    private void sidecar(long id, String origin, Long fileId, String offsetSource, boolean verified, Double fps)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            "INSERT INTO subtitle_sidecar VALUES (?, 1, ?, ?, ?, NULL, ?, " + (verified ? "now()" : "NULL") + ")")) {
            statement.setLong(1, id);
            statement.setString(2, origin);
            statement.setObject(3, fileId);
            statement.setObject(4, fps);
            statement.setString(5, offsetSource);
            statement.executeUpdate();
        }
    }

    private void reference(long id, long fileId, Double fps) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO opensubtitles_reference VALUES (?, 1, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, fileId);
            statement.setObject(3, fps);
            statement.executeUpdate();
        }
    }

    private Double fps(String table, long id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT timed_fps FROM " + table + " WHERE id = ?")) {
            statement.setLong(1, id);
            ResultSet found = statement.executeQuery();
            assertThat(found.next()).isTrue();
            double value = found.getDouble(1);
            return found.wasNull() ? null : value;
        }
    }

    private String retime(long id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT retime FROM subtitle_sidecar WHERE id = ?")) {
            statement.setLong(1, id);
            ResultSet found = statement.executeQuery();
            assertThat(found.next()).isTrue();
            return found.getString(1);
        }
    }

    private void migrate() throws Exception {
        new V40__BackfillSubtitleFrameRates().migrate(context);
    }

    @Test
    void aDownloadAndAReferenceTakeTheRateOfTheirFileFromEachOfTheThreeColumns() throws Exception {
        film("[{\"id\":1,\"fileId\":101,\"fps\":23.976}]", "[{\"id\":2,\"fileId\":102,\"fps\":25.0}]",
            "{\"id\":3,\"fileId\":103,\"fps\":24.0}");
        sidecar(1, "OPENSUBTITLES", 101L, null, false, null);
        sidecar(2, "OPENSUBTITLES", 102L, null, false, null);
        reference(1, 103L, null);
        reference(2, 101L, null);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isEqualTo(23.976);
        assertThat(fps("subtitle_sidecar", 2)).isEqualTo(25.0);
        assertThat(fps("opensubtitles_reference", 1)).isEqualTo(24.0);
        assertThat(fps("opensubtitles_reference", 2)).isEqualTo(23.976);
    }

    @Test
    void aRowItCannotMatchStaysNull() throws Exception {
        film("[{\"id\":1,\"fileId\":101,\"fps\":23.976}]", null, null);
        sidecar(1, "OPENSUBTITLES", 999L, null, false, null);
        reference(1, 998L, null);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isNull();
        assertThat(fps("opensubtitles_reference", 1)).isNull();
    }

    @Test
    void aResultThatStatesNoRateLeavesTheRowNull() throws Exception {
        film("[{\"id\":1,\"fileId\":101,\"fps\":null},{\"id\":2,\"fileId\":102}]", null, null);
        sidecar(1, "OPENSUBTITLES", 101L, null, false, null);
        sidecar(2, "OPENSUBTITLES", 102L, null, false, null);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isNull();
        assertThat(fps("subtitle_sidecar", 2)).isNull();
    }

    @Test
    void textThatIsNotJsonOrNotResultsAddsNothingAndDoesNotStopTheRest() throws Exception {
        film("not json", "[{\"fileId\":\"abc\",\"fps\":23.976}]", "{\"id\":3,\"fileId\":103,\"fps\":24.0}");
        sidecar(1, "OPENSUBTITLES", 103L, null, false, null);
        sidecar(2, "OPENSUBTITLES", 101L, null, false, null);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isEqualTo(24.0);
        assertThat(fps("subtitle_sidecar", 2)).isNull();
    }

    @Test
    void aFilmWithNoSearchResultsLeavesItsRowsNull() throws Exception {
        film(null, null, null);
        sidecar(1, "OPENSUBTITLES", 101L, null, false, null);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isNull();
    }

    @Test
    void theEarlierColumnWinsWhenAFileIsInMoreThanOne() throws Exception {
        film("[{\"id\":1,\"fileId\":101,\"fps\":23.976}]", "[{\"id\":1,\"fileId\":101,\"fps\":25.0}]", null);
        sidecar(1, "OPENSUBTITLES", 101L, null, false, null);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isEqualTo(23.976);
    }

    @Test
    void aRateAlreadyStoredAndAFileBesideTheFilmAreLeftAlone() throws Exception {
        film("[{\"id\":1,\"fileId\":101,\"fps\":23.976}]", null, null);
        sidecar(1, "OPENSUBTITLES", 101L, null, false, 25.0);
        sidecar(2, "FILE", null, null, false, null);
        reference(1, 101L, 25.0);

        migrate();

        assertThat(fps("subtitle_sidecar", 1)).isEqualTo(25.0);
        assertThat(fps("subtitle_sidecar", 2)).isNull();
        assertThat(fps("opensubtitles_reference", 1)).isEqualTo(25.0);
    }

    @Test
    void retimeIsOffOnlyForDownloadsTheOwnerTimedByHand() throws Exception {
        film(null, null, null);
        sidecar(1, "OPENSUBTITLES", 101L, "OWNER", false, null);
        sidecar(2, "OPENSUBTITLES", 102L, null, true, null);
        sidecar(3, "OPENSUBTITLES", 103L, "AUTO", false, null);
        sidecar(4, "OPENSUBTITLES", 104L, null, false, null);
        sidecar(5, "FILE", null, "OWNER", true, null);

        migrate();

        assertThat(retime(1)).isEqualTo("OFF");
        assertThat(retime(2)).isEqualTo("OFF");
        assertThat(retime(3)).isNull();
        assertThat(retime(4)).isNull();
        assertThat(retime(5)).isNull();
    }

    @Test
    void anEmptyDatabaseMigratesWithoutError() throws Exception {
        migrate();

        try (var statement = connection.createStatement();
             ResultSet found = statement.executeQuery("SELECT count(*) FROM subtitle_sidecar")) {
            assertThat(found.next()).isTrue();
            assertThat(found.getInt(1)).isZero();
        }
    }
}
