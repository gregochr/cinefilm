package gregochr.cinefilm.transcode;

import java.util.Locale;

/** Formats millisecond values as seconds with exactly three decimals, with integer arithmetic only. */
final class Seconds {

    private Seconds() {
    }

    /** {@code 4004} becomes {@code "4.004"}; {@code 0} becomes {@code "0.000"}. Non-negative input only. */
    static String format(long ms) {
        return String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000);
    }
}
