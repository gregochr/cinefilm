package gregochr.cinefilm.subtitle;

/**
 * The factor k that puts a subtitle's cue times on a film's timeline (docs/engineering/subtitle-frame-rate-plan.md):
 * the subtitle's stated frame rate over the film's, and only across the PAL speed-up (a 24 fps release against a
 * 25 fps copy, or the reverse). Everything else is exactly 1: an unknown rate on either side, a 30 fps copy (telecined,
 * so it runs at film speed), matching rates, and any rate outside the bands below.
 *
 * <p>The bands, all inclusive:
 * <ul>
 *   <li>a 25 fps film is 24.85 to 25.15, and its rate is taken as 25;</li>
 *   <li>a film of about 24 fps is 23.9 to 24.1; it is taken as 24.0 from 23.988 up and as 23.976 below that;</li>
 *   <li>a subtitle stated as 23.976 is within 0.01 of 23.976 (23.98 is in it); one stated as 24.0 is within 0.01 of
 *       24.0; one stated as 25 is within 0.01 of 25. A rate between these bands (23.9, 24.05, 24.98) is not one of the
 *       stated rates the plan retimes, so k is 1.</li>
 * </ul>
 */
public final class FrameRateRetime {

    private static final double FILM_24 = 23.976;
    private static final double FILM_24_EXACT = 24.0;
    private static final double PAL = 25.0;
    private static final double STATED_TOLERANCE = 0.01;
    private static final double SLACK = 1e-9;
    private static final double PAL_FILM_MIN = 24.85;
    private static final double PAL_FILM_MAX = 25.15;
    private static final double FILM_24_MIN = 23.9;
    private static final double FILM_24_MAX = 24.1;
    /** The midpoint of 23.976 and 24.0: a film below it is a 23.976 film, from it up a 24.0 film. */
    private static final double FILM_24_SPLIT = 23.988;

    private FrameRateRetime() {
    }

    /**
     * The factor to multiply every cue time by.
     *
     * @param subtitleFps the rate the subtitle was timed for, or null when unknown
     * @param filmFps the film file's rate, or null when unknown
     * @return k, exactly 1.0 when the subtitle needs no change
     */
    public static double factor(Double subtitleFps, Double filmFps) {
        if (subtitleFps == null || filmFps == null) {
            return 1.0;
        }
        double subtitle = subtitleFps;
        double film = filmFps;
        if (between(film, PAL_FILM_MIN, PAL_FILM_MAX)) {
            if (near(subtitle, FILM_24)) {
                return FILM_24 / PAL;
            }
            if (near(subtitle, FILM_24_EXACT)) {
                return FILM_24_EXACT / PAL;
            }
        } else if (between(film, FILM_24_MIN, FILM_24_MAX) && near(subtitle, PAL)) {
            return PAL / (film < FILM_24_SPLIT ? FILM_24 : FILM_24_EXACT);
        }
        return 1.0;
    }

    private static boolean between(double value, double min, double max) {
        return value >= min - SLACK && value <= max + SLACK;
    }

    private static boolean near(double value, double centre) {
        return Math.abs(value - centre) <= STATED_TOLERANCE + SLACK;
    }
}
