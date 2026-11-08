package gregochr.cinefilm.playback;

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

    /** Response of the watched endpoint. */
    public record WatchedResponse(boolean watched) {
    }

    /** Body of {@code PUT /api/films/{id}/preferences}; absent fields are left unchanged. */
    public record PreferencesRequest(Long editionId, String quality, Long audioStreamId, String subtitle) {
    }
}
