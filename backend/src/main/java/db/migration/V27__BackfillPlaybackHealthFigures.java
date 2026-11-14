package db.migration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills the figure columns that V26 added to {@code playback_health} for the rows stored before them (the table holds
 * at most 2,000). It is Java, not SQL, so the same code reads the JSON {@code summary} on PostgreSQL and on H2. A
 * {@code summary} that cannot be read leaves the column defaults and is not an error. Found by Flyway on the classpath
 * under {@code db/migration}, with no extra configuration.
 *
 * <p>A migration must do the same thing forever, so it does not use the application's own classes: the report fields
 * it reads and the {@code has_problem} rule (a stall, a reconnect, a fall back, a network error or a timeout) are
 * frozen here as they were when this migration was written, and a later change to the live extraction
 * ({@code HealthFigures}) or to the report type never changes what V27 does on a database that has not run it yet.
 */
public class V27__BackfillPlaybackHealthFigures extends BaseJavaMigration {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SELECT = "SELECT id, summary FROM playback_health";
    private static final String UPDATE = "UPDATE playback_health SET watched_ms = ?, stall_count = ?, stall_ms = ?, "
        + "startup_ms = ?, has_problem = ?, environment = ? WHERE id = ?";
    private static final int MAX_ENVIRONMENT = 16;

    /** Just the fields of a stored report that this migration reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Report(Long watchedMs, Long stallCount, Long stallMs, Long startupMs, Long reconnectCount,
                          Boolean fellBack, Long netErrors, Long timeouts, String environment) {
    }

    /** The figures written for one row. */
    private record Figures(long watchedMs, long stallCount, long stallMs, Long startupMs, boolean hasProblem,
                           String environment) {
    }

    private record Row(long id, String summary) {
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(SELECT); ResultSet found = select.executeQuery()) {
            while (found.next()) {
                rows.add(new Row(found.getLong(1), found.getString(2)));
            }
        }
        try (PreparedStatement update = connection.prepareStatement(UPDATE)) {
            for (Row row : rows) {
                Optional<Figures> figures = figuresOf(row.summary());
                if (figures.isPresent()) {
                    bind(update, figures.get(), row.id());
                    update.executeUpdate();
                }
            }
        }
    }

    private static Optional<Figures> figuresOf(String summary) {
        Report report;
        try {
            report = MAPPER.readValue(summary, Report.class);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (report == null) {
            return Optional.empty();
        }
        long stalls = zero(report.stallCount());
        boolean problem = stalls > 0 || zero(report.reconnectCount()) > 0 || Boolean.TRUE.equals(report.fellBack())
            || zero(report.netErrors()) > 0 || zero(report.timeouts()) > 0;
        String environment = report.environment() != null && report.environment().length() <= MAX_ENVIRONMENT
            ? report.environment() : null;
        return Optional.of(new Figures(zero(report.watchedMs()), stalls, zero(report.stallMs()), report.startupMs(),
            problem, environment));
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }

    private static void bind(PreparedStatement update, Figures figures, long id) throws SQLException {
        update.setLong(1, figures.watchedMs());
        update.setLong(2, figures.stallCount());
        update.setLong(3, figures.stallMs());
        if (figures.startupMs() == null) {
            update.setNull(4, Types.BIGINT);
        } else {
            update.setLong(4, figures.startupMs());
        }
        update.setBoolean(5, figures.hasProblem());
        update.setString(6, figures.environment());
        update.setLong(7, id);
    }
}
