package gregochr.cinefilm.transcode;

import java.util.List;

/** The request and response shapes of the playback-session API. */
public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * The body of {@code POST /api/editions/{id}/sessions}.
     *
     * @param audioStreamId an audio stream of the edition, or null for its default
     * @param startMs where playback starts in milliseconds, or null for the start
     * @param quality {@code auto}, {@code original} or a rung id
     * @param probeKbps the client's throughput probe in kilobits per second, or null
     */
    public record CreateRequest(Long audioStreamId, Long startMs, String quality, Long probeKbps) {
    }

    /**
     * One rung as the player sees it.
     *
     * @param id the rung id and URL segment
     * @param height picture height in lines
     * @param bitrate video bits per second
     * @param copy true when the video is the original, untouched
     */
    public record RenditionDto(String id, int height, long bitrate, boolean copy) {
    }

    /**
     * A created session.
     *
     * @param sessionId the session id
     * @param masterUrl where to load the master playlist
     * @param startRung the id of the rung listed first
     * @param renditions every rung the film has
     */
    public record SessionCreated(String sessionId, String masterUrl, String startRung,
                                 List<RenditionDto> renditions) {
    }

    /**
     * Admin's view of the transcoder.
     *
     * @param encoder {@code vaapi}, {@code software} or {@code unavailable}
     * @param index how many films have a keyframe index
     * @param hlsBytes bytes held in the {@code hls/} folder
     * @param sessions the live sessions
     */
    public record TranscodingStatus(String encoder, IndexDto index, long hlsBytes, List<SessionRow> sessions) {
    }

    /**
     * Keyframe index progress.
     *
     * @param indexed films with an index
     * @param total films that can be indexed
     */
    public record IndexDto(long indexed, long total) {
    }

    /**
     * One live session in Admin.
     *
     * @param id the session id
     * @param filmTitle the film's title
     * @param editionId the edition
     * @param rung the rung most recently requested
     * @param speed the encoder's speed as a multiple of real time, or null
     * @param segmentsAhead how many segments the encoder is ahead of the player
     * @param startedAt ISO-8601 start time
     * @param unavailable true after the encoder failed twice
     */
    public record SessionRow(String id, String filmTitle, long editionId, String rung, Double speed,
                             int segmentsAhead, java.time.Instant startedAt, boolean unavailable) {
    }
}
