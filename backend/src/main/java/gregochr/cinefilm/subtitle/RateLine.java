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
 * @param retimeAction the one per-file control that applies: {@code DONT} (retimed now), {@code RETIME}, or null for
 *     none (an owner's file, a file that fits as it is, an unknown rate, or a factor of 1)
 */
record RateLine(Double timedFps, Double filmFps, String retime, Double retimedBy, String line,
                String retimeAction) {

    /** The facts of a file that is not a download. */
    static final RateLine NONE = new RateLine(null, null, null, null, null, null);

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
            sentence(row, filmFps), action(row, filmFps));
    }

    /**
     * Which control the Admin offers. None when the file fits as it is, or when a rate is unknown or the factor is 1
     * (there is nothing to apply, whatever the choice); otherwise {@code DONT} while the file is retimed now, and
     * {@code RETIME} for any other (off, or not yet retimed: a hand-timed file, a film waiting for its English-track
     * fit, or the switch off), which retimes at once when pressed.
     */
    private static String action(SubtitleSidecar row, Double film) {
        Double timed = row.getTimedFps();
        if (row.getRetime() == RetimeChoice.OFF_FITS || timed == null || film == null
            || FrameRateRetime.factor(timed, film) == 1.0) {
            return null;
        }
        return row.getRetimedBy() != null ? "DONT" : "RETIME";
    }

    private static String sentence(SubtitleSidecar row, Double film) {
        Double timed = row.getTimedFps();
        RetimeChoice choice = row.getRetime();
        if (choice == RetimeChoice.OFF_FITS) {
            return "Fits this copy as it is";
        }
        if (row.getRetimedBy() != null) {
            // Worded from the film rate it was retimed for, not the film's rate now (a replaced copy, k now 1, or
            // an unknown rate): the file's cues are still scaled by that k.
            Double retimedFor = row.getRetimedForFps() != null ? row.getRetimedForFps() : film;
            return retimed(timed, retimedFor, row.getRetimedBy());
        }
        if (timed == null || film == null) {
            return "Frame rate unknown";
        }
        if (FrameRateRetime.sameRate(timed, film)) {
            return "Timed for this film's frame rate";
        }
        String timedFor = "Timed for " + fps(timed) + " fps; this film is " + fps(film) + " fps";
        if (FrameRateRetime.factor(timed, film) == 1.0) {
            return timedFor + "; only the 24 to 25 fps speed-up is retimed";
        }
        if ((choice == null || choice == RetimeChoice.OFF) && SubtitleRetimeService.timingSetByHand(row)) {
            // The job decides such a row off itself, so it is never retimed, whatever the owner chose.
            return "Not retimed: its timing was set by hand; it may drift";
        }
        if (choice == RetimeChoice.OFF) {
            return "Not retimed; it may drift";
        }
        return timedFor + ", so it is not retimed";
    }

    private static String retimed(Double timed, Double film, double retimedBy) {
        String timedFor = timed == null ? "Timed for an unknown frame rate" : "Timed for " + fps(timed) + " fps";
        String filmIs = film == null ? "this film's frame rate is unknown" : "this film is " + fps(film) + " fps";
        long percent = Math.round(Math.abs(1 - retimedBy) * 100);
        return timedFor + "; " + filmIs + ", so it is " + (retimedBy < 1 ? "sped up " : "slowed down ") + percent
            + "%";
    }

    /** A rate as people write it: at most three decimals, none when whole ("25", "23.976"). */
    private static String fps(double rate) {
        return BigDecimal.valueOf(rate).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
