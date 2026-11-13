package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.plex.PlexRules;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The command line of the labels tool.
 *
 * @param archive the Plex archive directory
 * @param export the database export CSV
 * @param preseed the file of film ids forced into development
 * @param prefix the Plex path prefix, normalised by {@code PlexRules.prefix}
 * @param date the run date
 * @param out the output directory
 * @param previousSplit the previous manifest, or null
 * @param exclusions the owner's exclusions CSV, or null
 */
record LabelsOptions(Path archive, Path export, Path preseed, String prefix, LocalDate date, Path out,
                     Path previousSplit, Path exclusions) {

    private static final List<String> REQUIRED = List.of("archive", "export", "preseed", "prefix", "date", "out");
    private static final List<String> OPTIONAL = List.of("previous-split", "exclusions");

    /**
     * Parses {@code --flag value} pairs.
     *
     * @param args the arguments
     * @return the options
     * @throws IllegalArgumentException with a one-line message when a flag is missing, repeated, unknown or has no
     *     value, or the date or prefix is not usable
     */
    static LabelsOptions parse(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String name = args[i];
            String flag = name.startsWith("--") ? name.substring(2) : name;
            if (!REQUIRED.contains(flag) && !OPTIONAL.contains(flag) || flag.equals(name)) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("flag " + name + " needs a value");
            }
            if (flags.put(flag, args[i + 1]) != null) {
                throw new IllegalArgumentException("flag " + name + " given twice");
            }
        }
        for (String name : REQUIRED) {
            if (!flags.containsKey(name)) {
                throw new IllegalArgumentException("missing required flag --" + name);
            }
        }
        String prefix = PlexRules.prefix(flags.get("prefix"));
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("--prefix must not be blank");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(flags.get("date"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("--date must be yyyy-MM-dd", e);
        }
        return new LabelsOptions(Path.of(flags.get("archive")), Path.of(flags.get("export")),
            Path.of(flags.get("preseed")), prefix, date, Path.of(flags.get("out")), optional(flags, "previous-split"),
            optional(flags, "exclusions"));
    }

    private static Path optional(Map<String, String> flags, String name) {
        return flags.containsKey(name) ? Path.of(flags.get(name)) : null;
    }
}
