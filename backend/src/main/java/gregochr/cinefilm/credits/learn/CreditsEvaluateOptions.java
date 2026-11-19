package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command line of the credits evaluation tool ({@code docs/engineering/credits-classifier-plan.md}, "L3c
 * specification", {@code CreditsEvaluateMain}). The only flag for test films is {@code --look=L5}. {@code --baseline}
 * is refused until L3c-eval-2b.
 */
record CreditsEvaluateOptions(Path features, List<Path> train, Path out, boolean look) {

    private static final String LOOK = "--look";
    private static final String LOOK_VALUE = "L5";
    private static final List<String> SINGLE = List.of("features", "out");
    private static final String TRAIN = "train";

    /**
     * Parses {@code --flag value} pairs ({@code --train} may repeat) and {@code --look=L5}.
     */
    static CreditsEvaluateOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        List<Path> train = new ArrayList<>();
        boolean look = false;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.equals(LOOK)) {
                throw new IllegalArgumentException("flag --look needs a value: --look=" + LOOK_VALUE);
            }
            if (name.startsWith(LOOK + "=")) {
                if (!name.equals(LOOK + "=" + LOOK_VALUE)) {
                    throw new IllegalArgumentException("--look must be " + LOOK_VALUE + ": " + name);
                }
                if (look) {
                    throw new IllegalArgumentException("flag --look given twice");
                }
                look = true;
                continue;
            }
            if (name.equals("--baseline")) {
                throw new IllegalArgumentException("--baseline is not supported yet");
            }
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (flag.equals(name) || !SINGLE.contains(flag) && !TRAIN.equals(flag)) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("flag " + name + " needs a value");
            }
            String value = args[++i];
            if (TRAIN.equals(flag)) {
                train.add(Path.of(value).toAbsolutePath().normalize());
            } else if (flags.put(flag, value) != null) {
                throw new IllegalArgumentException("flag " + name + " given twice");
            }
        }
        for (String name : List.of("features", "out")) {
            if (!flags.containsKey(name)) {
                throw new IllegalArgumentException("missing required flag --" + name);
            }
        }
        if (train.isEmpty()) {
            throw new IllegalArgumentException("missing required flag --" + TRAIN);
        }
        Set<Path> names = new HashSet<>();
        for (Path run : train) {
            if (!names.add(run.getFileName())) {
                throw new IllegalArgumentException("the same training run is given twice: " + run.getFileName());
            }
        }
        return new CreditsEvaluateOptions(Path.of(flags.get("features")), List.copyOf(train),
            Path.of(flags.get("out")), look);
    }
}
