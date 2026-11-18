package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command line of the credits features tool. There is no flag for test films, stills or a projection.
 *
 * @param data the dataset directory, holding {@code frames/<mediaFileId>/}
 * @param labels the labels file ({@code labels-<date>.json}) that says each film's side, fold and credits
 * @param out the output directory; each run makes a directory of its own inside it
 * @param ids the media file ids to read; empty means every directory under {@code frames/}
 */
record CreditsFeaturesOptions(Path data, Path labels, Path out, Set<Long> ids) {

    private static final List<String> REQUIRED = List.of("data", "labels", "out");
    private static final String IDS = "ids";

    /**
     * Parses {@code --flag value} pairs.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, or the ids are not usable
     */
    static CreditsFeaturesOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (flag.equals(name) || !REQUIRED.contains(flag) && !IDS.equals(flag)) {
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
        return new CreditsFeaturesOptions(Path.of(flags.get("data")), Path.of(flags.get("labels")),
            Path.of(flags.get("out")), DatasetMeasureOptions.ids(flags.get(IDS)));
    }
}
