package gregochr.cinefilm.transcode;

/**
 * One quality level of a film's HLS ladder.
 *
 * @param id the quality id and the URL segment: {@code original}, {@code 1080p}, {@code 720p}, ...
 * @param height the rung's picture height in lines
 * @param videoBitrate target video bits per second; for the copy rung the source's average bitrate
 * @param copy true when the video is copied untouched from the file
 */
public record Rung(String id, int height, long videoBitrate, boolean copy) {

    /** Peak factor applied to the source's average bitrate for the copy rung (per-segment sizes are not stored). */
    static final double COPY_PEAK_FACTOR = 1.5;

    /** Peak factor applied to a transcoded rung's target bitrate (rate control overshoot). */
    static final double TRANSCODED_PEAK_FACTOR = 1.2;

    /** AAC bits per second on every rung except the two lowest. */
    public static final long AUDIO_BITRATE = 128_000;

    /** AAC bits per second on the 360p and 240p rungs. */
    public static final long LOW_AUDIO_BITRATE = 96_000;

    /** AAC stereo bits per second for this rung. */
    public long audioBitrate() {
        return Ladder.Q_360.equals(id) || Ladder.Q_240.equals(id) ? LOW_AUDIO_BITRATE : AUDIO_BITRATE;
    }

    /** Average bits per second of video plus audio (HLS {@code AVERAGE-BANDWIDTH}). */
    public long averageBandwidth() {
        return videoBitrate + audioBitrate();
    }

    /**
     * Peak bits per second of video plus audio (HLS {@code BANDWIDTH}): the transcoded target times 1.2, or the
     * copy rung's average times 1.5, so the player never picks a rung the link cannot carry.
     */
    public long peakBandwidth() {
        double factor = copy ? COPY_PEAK_FACTOR : TRANSCODED_PEAK_FACTOR;
        return Math.round(videoBitrate * factor) + audioBitrate();
    }
}
