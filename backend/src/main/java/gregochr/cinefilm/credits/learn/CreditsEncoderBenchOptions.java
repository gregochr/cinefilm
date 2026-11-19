package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The command line of the encoder benchmark.
 *
 * @param encoder the encoder's directory, holding {@code encoder.json} and the ONNX file
 * @param out the output directory; each run makes a directory of its own inside it
 * @param frames the number of frames timed, 1 to 10,000
 * @param warmup the number of frames run first and not counted, 0 to 1,000
 */
record CreditsEncoderBenchOptions(Path encoder, Path out, int frames, int warmup) {

    /** The default number of timed frames: one film's coarse and dense frames. */
    static final int DEFAULT_FRAMES = 520;
    /** The default number of warm-up frames. */
    static final int DEFAULT_WARMUP = 16;
    static final int MAX_FRAMES = 10_000;
    static final int MAX_WARMUP = 1_000;

    private static final List<String> REQUIRED = List.of("encoder", "out");
    private static final List<String> NUMBERS = List.of("frames", "warmup");

    /**
     * Parses {@code --flag value} pairs.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, or a number is not a whole number in its range
     */
    static CreditsEncoderBenchOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (flag.equals(name) || !REQUIRED.contains(flag) && !NUMBERS.contains(flag)) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("flag " + name + " needs a value");
            }
            if (flags.put(flag, args[++i]) != null) {
                throw new IllegalArgumentException("flag " + name + " given twice");
            }
        }
        for (String name : REQUIRED) {
            if (!flags.containsKey(name)) {
                throw new IllegalArgumentException("missing required flag --" + name);
            }
        }
        return new CreditsEncoderBenchOptions(Path.of(flags.get("encoder")), Path.of(flags.get("out")),
            integer(flags, "frames", DEFAULT_FRAMES, 1, MAX_FRAMES),
            integer(flags, "warmup", DEFAULT_WARMUP, 0, MAX_WARMUP));
    }

    private static int integer(Map<String, String> flags, String name, int fallback, int min, int max) {
        String value = flags.get(name);
        if (value == null) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("flag --" + name + " needs a whole number: " + value, e);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("flag --" + name + " must be " + min + " to " + max + ": " + parsed);
        }
        return parsed;
    }
}
