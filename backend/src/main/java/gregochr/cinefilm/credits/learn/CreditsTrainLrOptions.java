package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command line of model B's training tool. There is no flag for test films: {@code --look} is an unknown
 * argument here, as is every other flag not listed.
 *
 * @param features the {@code features.csv} of a features run
 * @param summary the {@code summary.json} of the same run
 * @param manifest the split manifest ({@code split-<date>.json}); only its hash is used
 * @param embeddings an embeddings run's directory ({@code embeddings.bin} and {@code embeddings.json})
 * @param out the output directory; each run makes a directory of its own inside it
 * @param params the fit settings, the {@link LrParams#defaults()} unless a flag changes one
 * @param withDense whether dense frames are trained on as well as coarse ones
 * @param litTextVariant whether lit credits text that {@link FrameLabels} left out is relabelled as credits
 */
record CreditsTrainLrOptions(Path features, Path summary, Path manifest, Path embeddings, Path out, LrParams params,
                             boolean withDense, boolean litTextVariant) {

    private static final List<String> PATHS = List.of("features", "summary", "manifest", "embeddings", "out");
    private static final List<String> NUMBERS = List.of("l2", "max-iterations", "tolerance");
    private static final List<String> SWITCHES = List.of("with-dense", "lit-text-variant");

    /**
     * Parses {@code --flag value} pairs and the two bare switches.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, a number is not a number, or a setting is out of its range
     */
    static CreditsTrainLrOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        Set<String> switches = new HashSet<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (!flag.equals(name) && SWITCHES.contains(flag)) {
                if (!switches.add(flag)) {
                    throw new IllegalArgumentException("flag " + name + " given twice");
                }
                continue;
            }
            if (flag.equals(name) || !PATHS.contains(flag) && !NUMBERS.contains(flag)) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("flag " + name + " needs a value");
            }
            if (flags.put(flag, args[++i]) != null) {
                throw new IllegalArgumentException("flag " + name + " given twice");
            }
        }
        for (String name : PATHS) {
            if (!flags.containsKey(name)) {
                throw new IllegalArgumentException("missing required flag --" + name);
            }
        }
        LrParams d = LrParams.defaults();
        LrParams params = new LrParams(number(flags, "l2", d.l2()),
            integer(flags, "max-iterations", d.maxIterations()), number(flags, "tolerance", d.tolerance()));
        return new CreditsTrainLrOptions(Path.of(flags.get("features")), Path.of(flags.get("summary")),
            Path.of(flags.get("manifest")), Path.of(flags.get("embeddings")), Path.of(flags.get("out")), params,
            switches.contains("with-dense"), switches.contains("lit-text-variant"));
    }

    private static int integer(Map<String, String> flags, String name, int fallback) {
        String value = flags.get(name);
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("flag --" + name + " needs a whole number: " + value, e);
        }
    }

    private static double number(Map<String, String> flags, String name, double fallback) {
        String value = flags.get(name);
        try {
            return value == null ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("flag --" + name + " needs a number: " + value, e);
        }
    }
}
