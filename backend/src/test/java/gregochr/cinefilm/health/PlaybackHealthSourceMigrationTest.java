package gregochr.cinefilm.health;

import static gregochr.cinefilm.health.HealthSamples.client;
import static gregochr.cinefilm.health.HealthSamples.example;
import static gregochr.cinefilm.health.HealthSamples.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The V36 migration's own SQL, run against a plain in-memory H2 table (no Spring, no Flyway). */
class PlaybackHealthSourceMigrationTest {

    private static final String BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/130";
    private static final String HEADLESS = "Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/157.0.0.0 Safari/537.36";

    private Connection connection;

    @BeforeEach
    void table() throws SQLException {
        connection = DriverManager.getConnection(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE playback_health (id BIGINT PRIMARY KEY, summary VARCHAR(16000) NOT NULL)");
        }
    }

    @AfterEach
    void close() throws SQLException {
        connection.close();
    }

    private void insert(long id, ObjectNode report) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO playback_health (id, summary) VALUES (?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, text(report));
            statement.executeUpdate();
        }
    }

    private void migrate() throws SQLException, IOException {
        String sql;
        try (InputStream file = getClass().getResourceAsStream("/db/migration/V36__playback_health_source.sql")) {
            sql = new String(file.readAllBytes(), StandardCharsets.UTF_8);
        }
        String statements = Arrays.stream(sql.split("\n")).filter(line -> !line.startsWith("--"))
            .collect(Collectors.joining("\n"));
        try (Statement statement = connection.createStatement()) {
            for (String one : statements.split(";")) {
                if (!one.isBlank()) {
                    statement.execute(one);
                }
            }
        }
    }

    private String source(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT source FROM playback_health WHERE id = ?")) {
            statement.setLong(1, id);
            ResultSet found = statement.executeQuery();
            assertThat(found.next()).isTrue();
            return found.getString(1);
        }
    }

    @Test
    void aHeadlessUserAgentBecomesARobotAndAnOrdinaryBrowserStaysAPerson() throws Exception {
        ObjectNode robot = example();
        client(robot).put("userAgent", HEADLESS);
        ObjectNode person = example();
        client(person).put("userAgent", BROWSER);
        insert(1, robot);
        insert(2, person);

        migrate();

        assertThat(source(1)).isEqualTo("robot");
        assertThat(source(2)).isEqualTo("person");
    }

    @Test
    void theOperatorsTextCannotMakeARobot() throws Exception {
        ObjectNode report = example();
        report.put("operator", "HeadlessChrome/1");
        client(report).put("userAgent", BROWSER);
        insert(1, report);

        migrate();

        assertThat(source(1)).isEqualTo("person");
    }

    @Test
    void aQuoteInTheOperatorsTextIsEscapedSoItCannotForgeTheUserAgentKey() throws Exception {
        ObjectNode report = example();
        report.put("operator", "x\",\"userAgent\":\"HeadlessChrome/1");
        client(report).put("userAgent", BROWSER);
        insert(1, report);

        migrate();

        assertThat(source(1)).isEqualTo("person");
    }

    @Test
    void aRowWithNoClientStaysAPerson() throws Exception {
        ObjectNode report = example();
        report.remove("client");
        insert(1, report);

        migrate();

        assertThat(source(1)).isEqualTo("person");
    }
}
