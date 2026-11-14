package gregochr.cinefilm.credits.learn;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The command line of the dataset measurement tool.
 *
 * @param data the dataset directory, holding {@code frames/<mediaFileId>/}
 * @param out the output directory; each run makes a directory of its own inside it
 * @param labels the labels file ({@code labels-<date>.json}) that says each film's side
 * @param ids the media file ids to measure; empty means every directory under {@code frames/}
 * @param stills whether to write the stills too
 * @param project how many films the projected size is for
 * @param look {@code L5} or {@code gate} to include the test films, or null (the test films are always skipped)
 */
record DatasetMeasureOptions(Path data, Path out, Path labels, Set<Long> ids, boolean stills, long project,
                             String look) {

    /** The films the planned dataset holds, which the projected size is for unless {@code --project} says otherwise. */
    static final long DEFAULT_PROJECT = 850;

    private static final List<String> REQUIRED = List.of("data", "out", "labels");
    private static final List<String> OPTIONAL = List.of("ids", "project", "look");
    private static final List<String> LOOKS = List.of("L5", "gate");
    private static final String STILLS = "stills";
    private static final String LOOK_EQUALS = "--look=";

    /**
     * Parses {@code --flag value} pairs and the {@code --stills} switch.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, or the ids or project count is not usable
     */
    static DatasetMeasureOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        boolean stills = false;
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            if (name.startsWith(LOOK_EQUALS)) {
                if (flags.put("look", name.substring(LOOK_EQUALS.length())) != null) {
                    throw new IllegalArgumentException("flag --look given twice");
                }
                continue;
            }
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (flag.equals(name) || !REQUIRED.contains(flag) && !OPTIONAL.contains(flag) && !STILLS.equals(flag)) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            if (STILLS.equals(flag)) {
                if (stills) {
                    throw new IllegalArgumentException("flag " + name + " given twice");
                }
                stills = true;
                continue;
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
        String look = flags.get("look");
        if (look != null && !LOOKS.contains(look)) {
            throw new IllegalArgumentException("--look must be L5 or gate");
        }
        return new DatasetMeasureOptions(Path.of(flags.get("data")), Path.of(flags.get("out")),
            Path.of(flags.get("labels")), ids(flags.get("ids")), stills, project(flags.get("project")), look);
    }

    private static Set<Long> ids(String value) {
        Set<Long> ids = new TreeSet<>();
        if (value == null) {
            return ids;
        }
        for (String part : value.split(",", -1)) {
            try {
                ids.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--ids must be whole numbers separated by commas", e);
            }
        }
        return ids;
    }

    private static long project(String value) {
        if (value == null) {
            return DEFAULT_PROJECT;
        }
        try {
            long films = Long.parseLong(value);
            if (films > 0) {
                return films;
            }
        } catch (NumberFormatException e) {
            // Refused below, the same as a number that is not positive.
        }
        throw new IllegalArgumentException("--project must be a whole number above 0");
    }
}
