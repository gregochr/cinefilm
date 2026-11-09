package gregochr.cinefilm.playback;

import java.time.Instant;

/** Request and response bodies of the playback endpoints. */
public final class PlaybackDtos {

    private PlaybackDtos() {
    }

    /** Body of {@code PUT /api/editions/{id}/progress}. */
    public record ProgressRequest(Long positionMs) {
    }

    /** Response of the progress endpoint. */
    public record ProgressResponse(long positionMs, boolean watched) {
    }

    /** Body of {@code POST /api/editions/{id}/watched}. */
    public record WatchedRequest(Boolean watched) {
    }

    /** Response of the watched endpoint: the new flag and the state as it was just before the change. */
    public record WatchedResponse(boolean watched, PlaybackSnapshot previous) {
    }

    /**
     * The undoable part of an edition's playback state (never {@code lastPlayedAt}). Also the body of
     * {@code PUT /api/editions/{id}/playback-state}, which restores exactly these five fields.
     */
    public record PlaybackSnapshot(long positionMs, boolean watched, Instant watchedAt,
                                   boolean hiddenFromContinueWatching, Instant hiddenAt) {
    }

    /** Body of {@code PUT /api/editions/{id}/continue-watching}. */
    public record HiddenRequest(Boolean hidden) {
    }

    /** Response of the continue-watching endpoint. */
    public record HiddenResponse(boolean hidden) {
    }

    /** Body of {@code PUT /api/films/{id}/preferences}; absent fields are left unchanged. */
    public record PreferencesRequest(Long editionId, String quality, Long audioStreamId, String subtitle) {
    }
}
