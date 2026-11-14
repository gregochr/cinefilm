package db.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.health.HealthSamples;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The backfill's row logic, against a plain in-memory H2 table with the columns V26 adds (no Spring, no Flyway). */
class V27__BackfillPlaybackHealthFiguresTest {

    private Connection connection;
    private Context context;

    @BeforeEach
    void table() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE playback_health (id BIGINT PRIMARY KEY, summary VARCHAR(16000) NOT NULL, "
                + "watched_ms BIGINT NOT NULL DEFAULT 0, stall_count BIGINT NOT NULL DEFAULT 0, "
                + "stall_ms BIGINT NOT NULL DEFAULT 0, startup_ms BIGINT, has_problem BOOLEAN NOT NULL DEFAULT FALSE, "
                + "environment VARCHAR(16))");
        }
        context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    private void insert(long id, String summary) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO playback_health (id, summary) VALUES (?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, summary);
            statement.executeUpdate();
        }
    }

    private ResultSet row(long id) throws SQLException {
        var statement = connection.prepareStatement("SELECT * FROM playback_health WHERE id = ?");
        statement.setLong(1, id);
        ResultSet found = statement.executeQuery();
        assertThat(found.next()).isTrue();
        return found;
    }

    @Test
    void aReadableSummaryFillsEveryColumn() throws Exception {
        insert(1, HealthSamples.text(HealthSamples.example()));

        new V27__BackfillPlaybackHealthFigures().migrate(context);

        ResultSet found = row(1);
        assertThat(found.getLong("watched_ms")).isEqualTo(171_000);
        assertThat(found.getLong("stall_count")).isEqualTo(2);
        assertThat(found.getLong("stall_ms")).isEqualTo(9_400);
        assertThat(found.getLong("startup_ms")).isEqualTo(2_380);
        assertThat(found.getBoolean("has_problem")).isTrue();
        assertThat(found.getString("environment")).isEqualTo("train");
    }

    @Test
    void aSummaryWithNoStartupTimeLeavesItNull() throws Exception {
        var node = HealthSamples.example();
        node.remove("startupMs");
        insert(1, HealthSamples.text(node));

        new V27__BackfillPlaybackHealthFigures().migrate(context);

        ResultSet found = row(1);
        found.getLong("startup_ms");
        assertThat(found.wasNull()).isTrue();
        assertThat(found.getLong("watched_ms")).isEqualTo(171_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "", "[1,2]", "null", "{\"watchedMs\":\"abc\"}"})
    void aSummaryThatCannotBeReadKeepsTheDefaultsAndIsNotAnError(String summary) throws Exception {
        insert(1, summary);

        new V27__BackfillPlaybackHealthFigures().migrate(context);

        ResultSet found = row(1);
        assertThat(found.getLong("watched_ms")).isZero();
        assertThat(found.getLong("stall_count")).isZero();
        assertThat(found.getBoolean("has_problem")).isFalse();
        assertThat(found.getString("environment")).isNull();
    }

    @Test
    void eachRowIsFilledFromItsOwnSummaryAndAnUnreadableOneDoesNotStopTheRest() throws Exception {
        var first = HealthSamples.example();
        first.put("watchedMs", 1_000);
        var third = HealthSamples.example();
        third.put("watchedMs", 3_000);
        insert(1, HealthSamples.text(first));
        insert(2, "not json");
        insert(3, HealthSamples.text(third));

        new V27__BackfillPlaybackHealthFigures().migrate(context);

        assertThat(row(1).getLong("watched_ms")).isEqualTo(1_000);
        assertThat(row(2).getLong("watched_ms")).isZero();
        assertThat(row(3).getLong("watched_ms")).isEqualTo(3_000);
    }

    @Test
    void anEmptyTableIsFine() throws Exception {
        new V27__BackfillPlaybackHealthFigures().migrate(context);

        try (Statement statement = connection.createStatement();
             ResultSet count = statement.executeQuery("SELECT count(*) FROM playback_health")) {
            count.next();
            assertThat(count.getLong(1)).isZero();
        }
    }

    private boolean problemFor(String json) throws Exception {
        insert(1, json);
        new V27__BackfillPlaybackHealthFigures().migrate(context);
        return row(1).getBoolean("has_problem");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"stallCount\":1}", "{\"reconnectCount\":1}", "{\"fellBack\":true}",
        "{\"netErrors\":1}", "{\"timeouts\":1}"})
    void eachOfTheFiveSignsIsAProblem(String json) throws Exception {
        assertThat(problemFor(json)).isTrue();
    }

    @Test
    void aStepDownAloneIsNotAProblem() throws Exception {
        assertThat(problemFor("{\"stepsDown\":3,\"stepsUp\":1,\"watchedMs\":200000}")).isFalse();
    }

    @Test
    void anEnvironmentLongerThanTheColumnIsLeftNull() throws Exception {
        insert(1, "{\"environment\":\"" + "x".repeat(17) + "\"}");

        new V27__BackfillPlaybackHealthFigures().migrate(context);

        assertThat(row(1).getString("environment")).isNull();
    }
}
