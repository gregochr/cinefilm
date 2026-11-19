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
 * specification", {@code CreditsEvaluateMain}). {@code --look} and {@code --baseline}
 * are refused until L3c-eval-2b.
 */
record CreditsEvaluateOptions(Path features, List<Path> train, Path out) {

    private static final List<String> SINGLE = List.of("features", "out");
    private static final String TRAIN = "train";

    /** Parses {@code --flag value} pairs ({@code --train} may repeat). */
    static CreditsEvaluateOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        List<Path> train = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.startsWith("--look") || name.equals("--baseline")) {
                throw new IllegalArgumentException(name.split("=")[0] + " is not supported yet");
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
            Path.of(flags.get("out")));
    }
}
