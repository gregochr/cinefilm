package gregochr.cinefilm.transcode;

import java.util.Optional;

/**
 * Turns ffmpeg's {@code -progress} {@code key=value} lines into {@link EncoderProgress} values. A block ends with
 * {@code progress=continue} or {@code progress=end}; the report carries the last {@code out_time_us} (or
 * {@code out_time_ms}, which ffmpeg also gives in microseconds) and {@code speed} seen. Not thread-safe.
 */
final class ProgressParser {

    private long outTimeMs;
    private double speed;

    /** Feeds one line; returns the report when the line closes a progress block. */
    Optional<EncoderProgress> accept(String line) {
        int equals = line.indexOf('=');
        if (equals == -1) {
            return Optional.empty();
        }
        String key = line.substring(0, equals).strip();
        String value = line.substring(equals + 1).strip();
        if (key.equals("out_time_us") || key.equals("out_time_ms")) {
            outTimeMs = parseMicros(value);
        } else if (key.equals("speed")) {
            speed = parseSpeed(value);
        } else if (key.equals("progress")) {
            return Optional.of(new EncoderProgress(outTimeMs, speed));
        }
        return Optional.empty();
    }

    private long parseMicros(String value) {
        try {
            return Math.max(0, Long.parseLong(value) / 1000);
        } catch (NumberFormatException e) {
            return outTimeMs;
        }
    }

    private static double parseSpeed(String value) {
        String number = value.endsWith("x") ? value.substring(0, value.length() - 1).strip() : value;
        try {
            double parsed = Double.parseDouble(number);
            return Double.isFinite(parsed) ? Math.max(0, parsed) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
