package gregochr.cinefilm.summary;

import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import java.time.Instant;
import java.util.List;

/** The body of {@code GET /api/admin/summary}. */
public final class AdminSummaryDtos {

    private AdminSummaryDtos() {
    }

    /**
     * What needs the owner, by To do group.
     *
     * @param pickFilm films with no TMDB match ({@code needs-match} attention items)
     * @param severalFiles {@code multiple-files} and {@code parts-mismatch} attention items
     * @param fullMissing foreign-language films whose full English subtitles are unresolved
     * @param forcedQuestion films whose forced-subtitle question is unresolved
     */
    public record Todo(long pickFilm, long severalFiles, long fullMissing, long forcedQuestion) {
    }

    /**
     * The library at a glance.
     *
     * @param films films shown in browse, as the scan status counts them
     * @param lastScanFinishedAt when the last finished scan ended, or null when none has
     */
    public record Library(long films, Instant lastScanFinishedAt) {
    }

    /**
     * The Admin rail and To do strip's figures.
     *
     * @param todo the To do group counts
     * @param subtitlesUnresolved {@code fullMissing} plus {@code forcedQuestion}
     * @param live live HLS sessions, as {@code /api/admin/transcoding} lists them
     * @param library the library figures
     * @param playback film sessions of the last 30 days and how many played smoothly
     * @param playingNow viewings that reported in the last two minutes, newest first, at most 10
     */
    public record AdminSummary(Todo todo, long subtitlesUnresolved, int live, Library library,
                               PlaybackCounts playback, List<PlayingNow> playingNow) {
    }
}
