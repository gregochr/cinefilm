package gregochr.cinefilm.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Request and response bodies of the playback health endpoints. The report's fields are boxed so a missing one
 * arrives as null and is refused by {@link HealthRules} rather than failing in the JSON parser; unknown fields are
 * ignored, so a field added by a later release of the player does not break an older server.
 */
public final class HealthDtos {

    private HealthDtos() {
    }

    /** The body of {@code PUT /api/playback-health/{playbackId}}: the totals of one viewing so far. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthReport(Long seq, Long editionId, Long elapsedMs, String environment, String operator,
                               Long latencyMs, String engine, String quality, Boolean autoplayRefused,
                               Long startupMs, Long watchedMs, Long stallCount, Long stallMs, Long longestStallMs,
                               Long seekCount, Long seekWaitMs, Long reloadCount, Long reloadMs,
                               Long reconnectCount, Long reconnectMs, Long recoveries, Boolean fellBack,
                               Long stepsDown, Long stepsUp, Long netErrors, Long timeouts, Long kbpsLow,
                               Long kbpsMedian, Long kbpsHigh, Long kbpsSamples, Map<String, Long> rungMs,
                               List<List<Long>> minutes, ClientInfo client) {
    }

    /** The browser's own description of itself, part of a {@link HealthReport}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientInfo(String userAgent, String screen, Long cores) {
    }

    /** Response of {@code GET /api/admin/playback-health}. */
    public record PlaybackList(List<PlaybackRow> playbacks) {
    }

    /** One viewing as Admin sees it; {@code report} is the stored report as JSON. */
    public record PlaybackRow(String playbackId, Long filmId, String filmTitle, Long editionId, Instant startedAt,
                              Instant updatedAt, JsonNode report) {
    }

    /** Response of {@code GET /api/admin/playback-health} with a filter or a page: one page and the count of all. */
    public record PlaybackPage(List<PlaybackRow> playbacks, long total) {
    }

    /**
     * One film-length session as the chart needs it: the stored figures, the time on each rung and the browser's own
     * description of itself. {@code rungMs} and {@code userAgent} are null when the stored report cannot be read.
     */
    public record ChartRow(String playbackId, Long filmId, String filmTitle, Instant startedAt, long watchedMs,
                           long stallCount, long stallMs, Long startupMs, String environment,
                           Map<String, Long> rungMs, String userAgent) {
    }

    /** Response of {@code GET /api/admin/playback-health/chart}: the sessions, the newest last. */
    public record ChartList(List<ChartRow> sessions) {
    }
}
