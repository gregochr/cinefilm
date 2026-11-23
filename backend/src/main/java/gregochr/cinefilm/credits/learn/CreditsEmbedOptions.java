package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The command line of the embeddings tool. The only flag for test films is {@code --look=L5}.
 *
 * @param data the dataset directory, holding {@code frames/<mediaFileId>/}
 * @param features the features run's directory ({@code features-<stamp>}) whose rows are embedded
 * @param encoder the encoder's directory, holding {@code encoder.json} and the ONNX file
 * @param out the output directory; each run makes a directory of its own inside it
 * @param look whether {@code --look=L5} was given: the features run's test rows are embedded too, and the run
 *     writes {@code look.json}
 */
record CreditsEmbedOptions(Path data, Path features, Path encoder, Path out, boolean look) {

    private static final List<String> REQUIRED = List.of("data", "features", "encoder", "out");
    private static final String LOOK_FLAG = "--look";
    private static final String LOOK_VALUE = "L5";

    /**
     * Parses {@code --flag value} pairs.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, or {@code --look} is bare or has a value other than {@code L5}
     */
    static CreditsEmbedOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        boolean look = false;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.equals(LOOK_FLAG)) {
                throw new IllegalArgumentException("flag --look needs a value: --look=" + LOOK_VALUE);
            }
            if (name.startsWith(LOOK_FLAG + "=")) {
                if (!name.equals(LOOK_FLAG + "=" + LOOK_VALUE)) {
                    throw new IllegalArgumentException("--look must be " + LOOK_VALUE + ": " + name);
                }
                if (look) {
                    throw new IllegalArgumentException("flag --look given twice");
                }
                look = true;
                continue;
            }
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (flag.equals(name) || !REQUIRED.contains(flag)) {
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
        return new CreditsEmbedOptions(Path.of(flags.get("data")), Path.of(flags.get("features")),
            Path.of(flags.get("encoder")), Path.of(flags.get("out")), look);
    }
}
