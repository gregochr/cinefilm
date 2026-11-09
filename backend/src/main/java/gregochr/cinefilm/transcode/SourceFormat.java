package gregochr.cinefilm.transcode;

import java.util.Set;

/**
 * What the stored video format of a source allows. One predicate serves two decisions: the original may be copied
 * into MPEG-TS (the {@code original} rung), and the Intel i965 VAAPI driver can decode it on the GPU. Both hold only
 * for 8-bit 4:2:0 H.264: HEVC, High 10, 4:2:2 and 4:4:4 are neither safe to copy for Safari nor decodable by i965.
 * An unknown codec or pixel format (a file scanned before the pixel format was stored) is treated as not eligible.
 */
public final class SourceFormat {

    /** The display name {@code FfprobeParser} stores for H.264. */
    static final String H264 = "H.264";

    private static final Set<String> EIGHT_BIT_420 = Set.of("yuv420p", "yuvj420p");

    private SourceFormat() {
    }

    /**
     * Whether the video is 8-bit 4:2:0 H.264.
     *
     * @param videoCodec the stored display codec, or null
     * @param pixelFormat the stored ffprobe pixel format, or null
     * @return true only for H.264 with {@code yuv420p} or {@code yuvj420p}
     */
    public static boolean isH264Yuv420(String videoCodec, String pixelFormat) {
        return H264.equalsIgnoreCase(videoCodec) && pixelFormat != null
            && EIGHT_BIT_420.contains(pixelFormat.toLowerCase(java.util.Locale.ROOT));
    }
}
