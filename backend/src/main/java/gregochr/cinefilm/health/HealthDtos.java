package gregochr.cinefilm.health;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * The body of {@code PUT /api/playback-health/{playbackId}}: the totals of one playback so far.
     * {@code startPositionMs} and {@code endPositionMs} are the film position of the first and the latest beat; both
     * are optional (a report from a player that predates them has none, and a null is left out of the stored text, so
     * such a report is stored exactly as before) and, when present, at least 0.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HealthReport(Long seq, Long editionId, Long elapsedMs, String environment, String operator,
                               Long latencyMs, String engine, String quality, Boolean autoplayRefused,
                               Long startupMs, Long watchedMs, Long stallCount, Long stallMs, Long longestStallMs,
                               Long seekCount, Long seekWaitMs, Long reloadCount, Long reloadMs,
                               Long reconnectCount, Long reconnectMs, Long recoveries, Boolean fellBack,
                               Long stepsDown, Long stepsUp, Long netErrors, Long timeouts, Long kbpsLow,
                               Long kbpsMedian, Long kbpsHigh, Long kbpsSamples, Map<String, Long> rungMs,
                               List<List<Long>> minutes, ClientInfo client,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Long startPositionMs,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Long endPositionMs) {
    }

    /**
     * The browser's own description of itself, part of a {@link HealthReport}. {@code touch} is whether the device
     * reported more than one touch point ({@code navigator.maxTouchPoints > 1}). It is null in a report from a player
     * that predates it, and a null is left out of the stored text, so such a report is stored exactly as before. It
     * tells an iPad that sends a desktop Mac user agent from a Mac.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClientInfo(String userAgent, String screen, Long cores,
                             @JsonInclude(JsonInclude.Include.NON_NULL) Boolean touch) {
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
     * One viewing as the chart needs it: the stored figures of one or more playbacks of one film on one device (see
     * {@code ViewingJoiner}), the time on each rung and the browser's own description of itself. {@code playbackId} is
     * the first part's; {@code watchedMs}, {@code stallCount} and {@code stallMs} are sums; {@code rungMs} is summed
     * per rung (null when no part's stored report can be read); {@code startupMs}, {@code environment},
     * {@code userAgent} and {@code touch} are the first part's. {@code startedAt} is the first part's start and
     * {@code endedAt} the last part's last report. {@code parts} lists the playbacks, oldest first, never empty.
     * {@code touch} is the client's touch hint ({@link ClientInfo}); it is left out of the answer when the report has
     * none, so an old row reads as it did.
     */
    public record ChartRow(String playbackId, Long filmId, String filmTitle, Instant startedAt, long watchedMs,
                           long stallCount, long stallMs, Long startupMs, String environment,
                           Map<String, Long> rungMs, String userAgent,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean touch, Instant endedAt,
                           List<ChartPart> parts) {
    }

    /** One playback of a {@link ChartRow}: its id, when it started and last reported, what it played, where. */
    public record ChartPart(String playbackId, Instant startedAt, Instant endedAt, long watchedMs,
                            String environment) {
    }

    /** Response of {@code GET /api/admin/playback-health/chart}: the viewings, the newest last. */
    public record ChartList(List<ChartRow> sessions) {
    }

    /**
     * Film sessions of the last 30 days, for the Admin summary.
     *
     * @param films viewings (playbacks joined by {@code ViewingJoiner}) that started in the window and watched at
     *     least {@link PlaybackHealthService#FILM_SESSION_MIN_MS} in all
     * @param smooth those of them in which no part had a problem
     */
    public record PlaybackCounts(long films, long smooth) {
    }

    /**
     * One viewing that reported in the last two minutes, for the Admin summary's "Playing now".
     *
     * @param playbackId the viewing's id
     * @param editionId the edition being played
     * @param filmTitle the film's title, as copied at the first report
     * @param device the browser's user agent as reported, or null when the stored report cannot be read or carries
     *     none; the client words it
     * @param touch whether the device reported more than one touch point, or null when the report predates the field
     *     or the stored report cannot be read; with {@code device} it tells an iPad from a Mac
     * @param environment {@code home}, {@code train}, {@code away} or null when the report named none
     * @param quality the rung played in the report's last minute ({@code original}, {@code 1080p} ... {@code 240p}),
     *     else the quality the viewer chose ({@code auto} included); null when the stored report cannot be read
     */
    public record PlayingNow(String playbackId, long editionId, String filmTitle, String device, Boolean touch,
                             String environment, String quality) {
    }
}
