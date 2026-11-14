package gregochr.cinefilm.health;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.health.HealthDtos.ChartList;
import gregochr.cinefilm.health.HealthDtos.PlaybackRow;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The report route the player sends to, and the Admin routes that read the reports back. */
@RestController
public class PlaybackHealthController {

    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 9999;
    private static final List<String> LIST_PARAMETERS = List.of("since", "minWatchedMs", "problems", "page", "size");

    private final PlaybackHealthService health;

    /** Creates the controller over the health service. */
    public PlaybackHealthController(PlaybackHealthService health) {
        this.health = health;
    }

    /**
     * Records a report. The body is read here as UTF-8 text with a hard stop at
     * {@link PlaybackHealthService#MAX_BODY_LENGTH} characters, so an oversized body is never held in memory: it
     * reaches the service as null, which refuses it. A malformed body answers {@code Invalid health report}. A failure
     * of the connection itself ({@link IOException}) is not a bad report and propagates.
     */
    @PutMapping("/api/playback-health/{playbackId}")
    ResponseEntity<Void> report(@PathVariable String playbackId, HttpServletRequest request) throws IOException {
        String body = BoundedText.read(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8),
            PlaybackHealthService.MAX_BODY_LENGTH);
        try {
            health.report(playbackId, body);
        } catch (DataIntegrityViolationException e) {
            // Two first reports of one viewing can race (closing the tab before the first frame sends two): the loser's
            // insert fails on the unique playback id. Its transaction has rolled back and the winner's row is now
            // there, so asking once more takes the update path, where the higher seq wins. A second failure is a
            // different problem and propagates.
            health.report(playbackId, body);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The viewings. With none of the query parameters present, the 20 most recently updated and no {@code total}, as
     * ever; with any present (whatever its value), the filtered, paged list newest start first. A parameter that is
     * present must hold a valid value: an empty or malformed one is a 400 {@code Invalid request}.
     */
    @GetMapping("/api/admin/playback-health")
    ResponseEntity<?> list(@RequestParam Map<String, String> params) {
        if (LIST_PARAMETERS.stream().noneMatch(params::containsKey)) {
            return ResponseEntity.ok(health.recent());
        }
        return ResponseEntity.ok(health.query(parse(params, "since", PlaybackHealthController::since),
            parse(params, "minWatchedMs", Long::parseLong), parse(params, "problems", PlaybackHealthController::bool),
            parse(params, "page", Integer::parseInt), parse(params, "size", Integer::parseInt)));
    }

    /** The film sessions since a time (all when omitted), with only the chart's fields. */
    @GetMapping("/api/admin/playback-health/chart")
    ChartList chart(@RequestParam Map<String, String> params) {
        return health.chart(parse(params, "since", PlaybackHealthController::since));
    }

    /** A present parameter read with {@code reader}; null when absent; 400 when empty or malformed. */
    private static <T> T parse(Map<String, String> params, String name, Function<String, T> reader) {
        String value = params.get(name);
        if (value == null) {
            return null;
        }
        try {
            return reader.apply(value);
        } catch (RuntimeException e) {
            throw new BadRequestException("Invalid request");
        }
    }

    /**
     * An ISO instant in the years 1970 to 9999. That is a sanity range, not the database's: an earlier or later year
     * is a typo, and an extreme one can fail the JDBC conversion with a 500.
     */
    private static Instant since(String value) {
        Instant parsed = Instant.parse(value);
        int year = parsed.atZone(ZoneOffset.UTC).getYear();
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("out of range");
        }
        return parsed;
    }

    private static Boolean bool(String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.valueOf(value);
        }
        throw new IllegalArgumentException("not a boolean");
    }

    @GetMapping("/api/admin/playback-health/{playbackId}")
    PlaybackRow one(@PathVariable String playbackId) {
        return health.one(playbackId);
    }
}
