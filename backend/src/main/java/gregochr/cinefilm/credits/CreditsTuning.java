package gregochr.cinefilm.credits;

/**
 * Every threshold of the credits analysis in one place. Phase 2 tunes these from real films; nothing else in the
 * package carries a magic number. Each is biased to "late or nothing": a missed credits start costs the fallback, an
 * early one interrupts the film.
 */
final class CreditsTuning {

    // ---- Frame measuring -------------------------------------------------------------------------------------

    /** The peak is the luma at this share of all pixels, so a few specks do not set it. */
    static final double PEAK_PERCENTILE = 0.995;
    /** The peak never drops below this, so an all-dark window cannot make noise look bright. */
    static final int PEAK_FLOOR = 60;
    /** A pixel is dark below this share of the peak. */
    static final double DARK_FRACTION = 0.15;
    /** A pixel is never dark above this luma regardless of the peak (limited-range black is 16). */
    static final int DARK_MIN_LUMA = 20;
    /** A pixel is bright above this share of the peak. */
    static final double BRIGHT_FRACTION = 0.65;
    /** The pixel beside a text run must be at or below this share of the peak for the edge to be sharp. */
    static final double SHARP_EDGE_FRACTION = 0.5;
    /** A text run is at most this share of the active width wide... */
    static final double THIN_RUN_WIDTH_FRACTION = 0.06;
    /** ...but never allowed to be narrower than this many pixels. */
    static final int THIN_RUN_MIN_PX = 4;
    /** A row only counts as a line of text when it holds at least this many thin, sharp runs. */
    static final int MIN_RUNS_PER_TEXT_ROW = 3;
    /** The central band is the width minus this share at each side (the central 70%). */
    static final double CENTRAL_MARGIN_FRACTION = 0.15;
    /** At least this share of the bright pixels must be central text for the frame to be text-like. */
    static final double TEXT_LIKE_SHARE = 0.8;

    // ---- Frame classes ---------------------------------------------------------------------------------------

    /** A frame is {@code BLACK} from this dark share. */
    static final double BLACK_DARK_SHARE = 0.98;
    /** A frame is {@code TEXT_ON_DARK} from this dark share... */
    static final double TEXT_DARK_SHARE = 0.80;
    /** ...with a bright share of at least this... */
    static final double TEXT_BRIGHT_MIN = 0.002;
    /** ...and at most this. */
    static final double TEXT_BRIGHT_MAX = 0.20;

    // ---- Active area -----------------------------------------------------------------------------------------

    /** A bar pixel is at or below this luma. */
    static final int BAR_MAX_LUMA = 18;
    /** A row or column is still a bar when at most this share of its pixels are above the bar luma. */
    static final double BAR_STRAY_SHARE = 0.002;
    /** An active area smaller than this share of the frame in either dimension is implausible. */
    static final double MIN_ACTIVE_SHARE = 0.5;

    // ---- Motion ----------------------------------------------------------------------------------------------

    /** Fewer frames than this cannot show motion. */
    static final int MOTION_MIN_FRAMES = 8;
    /** At least this share of the frames must not be picture. */
    static final double MOTION_MIN_NON_PICTURE_SHARE = 0.8;
    /** At least this share of the frames must be text on dark. */
    static final double MOTION_MIN_TEXT_SHARE = 0.5;
    /** The largest crawl step per frame, as a share of the active height. */
    static final double CRAWL_MAX_SHIFT_SHARE = 1.0 / 6;
    /** A shifted row profile matches when its relative error is at most this. */
    static final double CRAWL_MATCH_MAX_ERROR = 0.15;
    /** ...and its error is at most this share of the unshifted error. */
    static final double CRAWL_BETTER_THAN_STILL = 0.5;
    /** At least this share of the pairs showing any text must move for a crawl. */
    static final double CRAWL_MIN_MOVING_SHARE = 0.7;
    /** At least this share of the moving pairs must step by the median amount, give or take one pixel. */
    static final double CRAWL_STEADY_SHARE = 0.8;
    /** A pixel changed when it differs by more than this share of the peak. */
    static final double CARD_CHANGE_DIFF_FRACTION = 0.4;
    /** A pair of frames changed abruptly when at least this share of the pixels changed. */
    static final double CARD_CHANGE_MIN_SHARE = 0.005;
    /** A pair of frames is still when at most this share of the pixels changed. */
    static final double CARD_STILL_MAX_SHARE = 0.001;
    /** Cards need at least this many abrupt changes. */
    static final int CARD_MIN_CHANGES = 2;
    /** At least this share of the pairs must be still for cards. */
    static final double CARD_MIN_STILL_SHARE = 0.5;

    // ---- Finding the start -----------------------------------------------------------------------------------

    /** The spacing of the coarse samples. */
    static final long SAMPLE_MS = 5_000;
    /** Longest stretch of picture allowed inside the credits run or after it. */
    static final long MAX_PICTURE_GAP_MS = 120_000;
    /** The run must last at least this long... */
    static final long MIN_RUN_MS = 60_000;
    /** ...with at least this much text on dark. */
    static final long MIN_TEXT_MS = 30_000;
    /** Motion must show within this long of the first text. */
    static final long MOTION_WINDOW_MS = 30_000;
    /** At most this much black before the first text counts as the start. */
    static final long MAX_LEADING_BLACK_MS = 10_000;
    /** Dialogue this long after the found start contradicts it. */
    static final long MAX_DIALOGUE_AFTER_START_MS = 30_000;
    /** A silence this long ends the dialogue that counts. */
    static final long DIALOGUE_GAP_MS = 120_000;

    // ---- Edition guard ---------------------------------------------------------------------------------------

    /** The marker must be at least this percent into the edition. */
    static final int MIN_EDITION_PERCENT = 60;
    /** The marker must be at least this long before the edition's end. */
    static final long MIN_BEFORE_END_MS = 60_000;

    private CreditsTuning() {
    }
}
