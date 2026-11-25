package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills {@code timed_fps} (added by V39) on the downloads and references stored before it, from the film's search
 * results: each row's OpenSubtitles file id is looked up in {@code film.os_candidates}, {@code film.os_full_candidates}
 * and {@code film.os_best}, whose results carry {@code fileId} and {@code fps}. A row it cannot match (the picker's
 * results were kept in memory only), or whose result states no rate, stays null. It is Java so the same code reads the
 * JSON on PostgreSQL and on H2. It also sets {@code retime = 'OFF'} on downloads the owner timed by hand (an OWNER
 * offset, or an owner verification), so the retime will leave them as they play; every other {@code retime} stays null.
 *
 * <p>A migration must do the same thing forever, so it does not use the application's own classes: the JSON keys it
 * reads are frozen here as they were when this migration was written.
 */
public class V40__BackfillSubtitleFrameRates extends BaseJavaMigration {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SIDECARS = "SELECT s.id, e.film_id, s.opensubtitles_file_id FROM subtitle_sidecar s "
        + "JOIN media_file m ON m.id = s.media_file_id JOIN edition e ON e.id = m.edition_id "
        + "WHERE s.origin = 'OPENSUBTITLES' AND s.timed_fps IS NULL AND s.opensubtitles_file_id IS NOT NULL";
    private static final String REFERENCES = "SELECT id, film_id, os_file_id FROM opensubtitles_reference "
        + "WHERE timed_fps IS NULL";
    private static final String FILM = "SELECT os_candidates, os_full_candidates, os_best FROM film WHERE id = ?";
    private static final String UPDATE_SIDECAR = "UPDATE subtitle_sidecar SET timed_fps = ? WHERE id = ?";
    private static final String UPDATE_REFERENCE = "UPDATE opensubtitles_reference SET timed_fps = ? WHERE id = ?";
    private static final String RETIME_OFF = "UPDATE subtitle_sidecar SET retime = 'OFF' "
        + "WHERE origin = 'OPENSUBTITLES' AND (offset_source = 'OWNER' OR owner_verified_at IS NOT NULL)";

    /** A stored row and the file it was made from. */
    private record Row(long id, long filmId, long fileId) {
    }

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        Map<Long, Map<Long, Double>> ratesByFilm = new HashMap<>();
        fill(connection, SIDECARS, UPDATE_SIDECAR, ratesByFilm);
        fill(connection, REFERENCES, UPDATE_REFERENCE, ratesByFilm);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(RETIME_OFF);
        }
    }

    private static void fill(Connection connection, String select, String update,
                             Map<Long, Map<Long, Double>> ratesByFilm) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(select);
             ResultSet found = statement.executeQuery()) {
            while (found.next()) {
                rows.add(new Row(found.getLong(1), found.getLong(2), found.getLong(3)));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            for (Row row : rows) {
                if (!ratesByFilm.containsKey(row.filmId())) {
                    ratesByFilm.put(row.filmId(), ratesOf(connection, row.filmId()));
                }
                Double fps = ratesByFilm.get(row.filmId()).get(row.fileId());
                if (fps != null) {
                    statement.setDouble(1, fps);
                    statement.setLong(2, row.id());
                    statement.executeUpdate();
                }
            }
        }
    }

    /** The stated rate of every result of a film that states one, by file id; an earlier column wins a repeat. */
    private static Map<Long, Double> ratesOf(Connection connection, long filmId) throws SQLException {
        Map<Long, Double> rates = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(FILM)) {
            statement.setLong(1, filmId);
            try (ResultSet found = statement.executeQuery()) {
                while (found.next()) {
                    for (int column = 1; column <= 3; column++) {
                        collect(found.getString(column), rates);
                    }
                }
            }
        }
        return rates;
    }

    /** Adds the rates of a column that is a result or an array of results; text that is not JSON adds nothing. */
    private static void collect(String json, Map<Long, Double> rates) {
        if (json == null) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            return;
        }
        if (root.isArray()) {
            for (JsonNode element : root) {
                add(element, rates);
            }
        } else {
            add(root, rates);
        }
    }

    private static void add(JsonNode result, Map<Long, Double> rates) {
        JsonNode fileId = result.path("fileId");
        JsonNode fps = result.path("fps");
        if (fileId.isIntegralNumber() && fps.isNumber()) {
            rates.putIfAbsent(fileId.asLong(), fps.asDouble());
        }
    }
}
