package gregochr.cinefilm.library;

import java.util.List;

/**
 * What ffprobe told us about a file, already reduced to what CineFilm stores.
 *
 * @param durationMs duration in milliseconds, 0 when unknown
 * @param container short container name such as {@code mp4}
 * @param videoCodec display codec such as {@code H.264}, or null when there is no video stream
 * @param width video width, or null
 * @param height video height, or null
 * @param bitrate overall bit rate in bits per second, or null
 * @param audio every audio stream
 * @param subtitles every text subtitle stream CineFilm can convert to WebVTT
 * @param tags the format-level tags
 * @param protectedContent true when a video or audio stream is FairPlay-protected
 */
public record ProbeResult(long durationMs, String container, String videoCodec, Integer width, Integer height,
                          Long bitrate, List<Audio> audio, List<Subtitle> subtitles, Tags tags,
                          boolean protectedContent) {

    /**
     * An audio stream.
     *
     * @param index ffprobe stream index in the file
     * @param language language name such as {@code English}, or null
     * @param channels {@code Mono}, {@code Stereo}, {@code 5.1}, {@code 7.1} or {@code N ch}
     * @param codec display codec such as {@code AAC} or {@code AC-3}
     * @param title the track title, or null
     * @param isDefault true when flagged as the default track
     * @param safariPlayable true when Safari can decode the codec
     */
    public record Audio(int index, String language, String channels, String codec, String title, boolean isDefault,
                        boolean safariPlayable) {
    }

    /**
     * A text subtitle stream.
     *
     * @param index ffprobe stream index in the file
     * @param language language name, or null
     * @param forced true when flagged forced
     * @param title the track title, or null
     * @param format the ffprobe codec name such as {@code mov_text}
     */
    public record Subtitle(int index, String language, boolean forced, String title, String format) {
    }

    /**
     * Format-level metadata tags.
     *
     * @param title the embedded {@code title}, or null
     * @param year the year from {@code date} or {@code ©day}, or null
     * @param mediaType the iTunes {@code media_type}, or null
     * @param hdVideo the iTunes {@code hd_video} flag, or null
     * @param description the embedded description, or null
     */
    public record Tags(String title, Integer year, String mediaType, String hdVideo, String description) {
    }
}
