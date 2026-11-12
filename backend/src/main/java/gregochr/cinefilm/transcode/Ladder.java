package gregochr.cinefilm.transcode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which rungs a film has: a pure function of the source height, whether the original can be copied, and what the
 * encoder can do. Rungs are listed best first; a ladder whose top rung is above 240 lines ends in the 240p floor
 * rung, which a very slow link can still carry. Every rung is strictly shorter than the one above it (apart from a
 * top rung that is transcoded at the source's own height), so heights are unique within a film and a player can tell
 * the active rung from {@code videoHeight}.
 */
public final class Ladder {

    /** Quality id: let the player adapt. */
    public static final String AUTO = "auto";
    /** Quality id: the untouched source video. */
    public static final String ORIGINAL = "original";
    /** Quality id: transcoded, above 720 lines. */
    public static final String Q_1080 = "1080p";
    /** Quality id: 720 lines (or a source between 481 and 720 lines transcoded at its own height). */
    public static final String Q_720 = "720p";
    /** Quality id: 480 lines. */
    public static final String Q_480 = "480p";
    /** Quality id: 360 lines. */
    public static final String Q_360 = "360p";
    /**
     * Quality id: 240 lines. A ladder whose top rung is above 240 lines ends in it as the floor rung at
     * {@link Rung#FLOOR_VIDEO_BITRATE}; a source that cannot be copied and has 241 lines or fewer is encoded at 240
     * lines or fewer and has it as its only rung at 400 kbit/s; so the id has two possible bitrates and never both
     * in one film.
     */
    public static final String Q_240 = "240p";

    /** Every quality id the API accepts, in the order the player lists them. */
    public static final List<String> QUALITY_IDS = List.of(AUTO, ORIGINAL, Q_1080, Q_720, Q_480, Q_360, Q_240);

    /** Source heights above this are transcoded at this height. */
    static final int MAX_TRANSCODED_HEIGHT = 1080;

    /** Used for the copy rung when the file's bitrate is unknown. */
    static final long DEFAULT_COPY_BITRATE = 5_000_000;

    private static final Set<String> SOFTWARE_IDS = Set.of(Q_480, Q_360, Q_240);

    private record Level(String id, int height, long bitrate) {
    }

    private static final Level TOP_1080 = new Level(Q_1080, 1080, 5_000_000);
    private static final Level TOP_720 = new Level(Q_720, 720, 2_500_000);
    private static final Level TOP_480 = new Level(Q_480, 480, 1_000_000);
    private static final Level TOP_360 = new Level(Q_360, 360, 600_000);
    private static final Level TOP_240 = new Level(Q_240, 240, 400_000);
    private static final Level FLOOR_240 = new Level(Q_240, 240, Rung.FLOOR_VIDEO_BITRATE);

    private static final List<Level> LOWER = List.of(TOP_720, TOP_480, TOP_360, FLOOR_240);

    private Ladder() {
    }

    /**
     * The rungs of a film.
     *
     * @param sourceHeight the video height of the file, at least 2
     * @param sourceBitrate the file's average bits per second (0 or less when unknown)
     * @param copyOk whether the file has a keyframe index with gaps of at most 12 s (see {@link CutList#copyOk()})
     * @param mode what the server can encode with: {@code SOFTWARE} keeps only the 480p, 360p and 240p transcoded
     *     rungs and {@code UNAVAILABLE} none (the copy rung is unchanged either way)
     * @return the rungs, best first; empty only for {@code UNAVAILABLE} without a copy rung
     */
    public static List<Rung> rungs(int sourceHeight, long sourceBitrate, boolean copyOk, EncoderMode mode) {
        if (sourceHeight < 2) {
            throw new IllegalArgumentException("source height must be at least 2");
        }
        List<Rung> rungs = new ArrayList<>();
        List<Rung> transcoded = new ArrayList<>();
        int limit = sourceHeight;
        if (copyOk) {
            rungs.add(new Rung(ORIGINAL, sourceHeight, sourceBitrate > 0 ? sourceBitrate : DEFAULT_COPY_BITRATE,
                true));
        } else {
            int top = Math.min(sourceHeight, MAX_TRANSCODED_HEIGHT) / 2 * 2;
            Level level = topLevel(top);
            transcoded.add(new Rung(level.id(), top, level.bitrate(), false));
            limit = top;
        }
        for (Level level : LOWER) {
            if (level.height() < limit) {
                transcoded.add(new Rung(level.id(), level.height(), level.bitrate(), false));
            }
        }
        if (mode == EncoderMode.SOFTWARE) {
            transcoded.removeIf(rung -> !SOFTWARE_IDS.contains(rung.id()));
        }
        if (mode != EncoderMode.UNAVAILABLE) {
            rungs.addAll(transcoded);
        }
        return List.copyOf(rungs);
    }

    /**
     * The rungs of a film from its stored facts: the one place that decides whether the original can be copied, used
     * by both the session service and the film detail's quality list so the two always agree. The original is copied
     * only when it is 8-bit 4:2:0 H.264 (see {@link SourceFormat}) and its keyframe gaps allow it.
     *
     * <p>When the keyframe index does not exist yet the supplier returns null and the codec and pixel format decide
     * alone (an indexed file with long gaps is only discovered, and its list corrected, when it is first played).
     * The supplier is not called for a source whose format already rules the copy out.
     *
     * @param sourceHeight the video height, at least 2
     * @param sourceBitrate the file's average bits per second (0 or less when unknown)
     * @param videoCodec the stored display codec, or null
     * @param pixelFormat the stored pixel format, or null (not eligible)
     * @param cuts the film's cut list, or null from the supplier when it is not indexed
     * @param mode what the server can encode with
     * @return the rungs, best first
     */
    public static List<Rung> forSource(int sourceHeight, long sourceBitrate, String videoCodec, String pixelFormat,
                                       Supplier<CutList> cuts, EncoderMode mode) {
        boolean copyOk = SourceFormat.isH264Yuv420(videoCodec, pixelFormat);
        if (copyOk) {
            CutList indexed = cuts.get();
            copyOk = indexed == null || indexed.copyOk();
        }
        return rungs(sourceHeight, sourceBitrate, copyOk, mode);
    }

    private static Level topLevel(int height) {
        if (height > 720) {
            return TOP_1080;
        }
        if (height > 480) {
            return TOP_720;
        }
        if (height > 360) {
            return TOP_480;
        }
        return height > 240 ? TOP_360 : TOP_240;
    }
}
