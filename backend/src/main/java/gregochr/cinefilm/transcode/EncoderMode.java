package gregochr.cinefilm.transcode;

/** What the server can encode with, detected once at start-up by {@link EncoderProbe}. */
public enum EncoderMode {
    /** Intel Quick Sync through VAAPI ({@code h264_vaapi}). */
    VAAPI,
    /** {@code libx264}: slower, so {@link Ladder} offers fewer transcoded rungs. */
    SOFTWARE,
    /** No usable ffmpeg: only the original can be played. */
    UNAVAILABLE
}
