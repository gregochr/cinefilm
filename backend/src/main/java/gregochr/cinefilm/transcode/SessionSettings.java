package gregochr.cinefilm.transcode;

import java.time.Duration;

/**
 * The limits the session manager works to.
 *
 * @param idleStop no request for this long stops the session's encoder
 * @param expiry no request for this long deletes the session's files and encoder; the session is
 *     remembered and rebuilt by a later request (see {@link SessionManager})
 * @param hlsCapBytes the most the {@code hls/} folder may hold before idle sessions are evicted
 * @param threads ffmpeg's thread bound
 * @param device the VAAPI render node ({@code cinefilm.transcode.device})
 */
public record SessionSettings(Duration idleStop, Duration expiry, long hlsCapBytes, int threads, String device) {
}
