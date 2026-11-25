package gregochr.cinefilm.subtitle;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The frame-rate facts of a downloaded subtitle for the Admin, and the one plain sentence worked out from them
 * (docs/engineering/subtitle-frame-rate-plan.md, "The owner's rate"). Every field is null for an owner's file.
 *
 * @param timedFps the rate the subtitle is stated to be timed for, or null
 * @param filmFps the film's rate for the edition the download was made for, or null
 * @param retime {@code AUTO}, {@code OFF}, {@code OFF_FITS}, or null while undecided
 * @param retimedBy the factor the stored cues were scaled by, or null when never retimed
 * @param line the sentence the Admin shows as it is
 */
record RateLine(Double timedFps, Double filmFps, String retime, Double retimedBy, String line) {

    /** The facts of a file that is not a download. */
    static final RateLine NONE = new RateLine(null, null, null, null, null);

    /**
     * The facts of one row.
     *
     * @param row the subtitle
     * @param filmFps the film's rate for the row's edition, or null when unknown
     * @return {@link #NONE} for an owner's file
     */
    static RateLine of(SubtitleSidecar row, Double filmFps) {
        if (!row.isDownload()) {
            return NONE;
        }
        RetimeChoice choice = row.getRetime();
        return new RateLine(row.getTimedFps(), filmFps, choice == null ? null : choice.name(), row.getRetimedBy(),
            sentence(row.getTimedFps(), filmFps, choice, row.getRetimedBy()));
    }

    private static String sentence(Double timed, Double film, RetimeChoice choice, Double retimedBy) {
        if (choice == RetimeChoice.OFF_FITS) {
            return "Fits this copy as it is";
        }
        if (timed == null || film == null) {
            return "Frame rate unknown";
        }
        double k = FrameRateRetime.factor(timed, film);
        if (k == 1.0) {
            return "Timed for this film's frame rate";
        }
        if (choice == RetimeChoice.OFF) {
            return "Not retimed (you chose this); it may drift";
        }
        String timedFor = "Timed for " + fps(timed) + " fps; this film is " + fps(film) + " fps";
        if (retimedBy == null) {
            return timedFor + ", so it is not retimed yet";
        }
        long percent = Math.round(Math.abs(1 - retimedBy) * 100);
        return timedFor + ", so it is " + (retimedBy < 1 ? "sped up " : "slowed down ") + percent + "%";
    }

    /** A rate as people write it: at most three decimals, none when whole ("25", "23.976"). */
    private static String fps(double rate) {
        return BigDecimal.valueOf(rate).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
