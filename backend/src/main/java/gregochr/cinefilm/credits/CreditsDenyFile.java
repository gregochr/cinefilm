package gregochr.cinefilm.credits;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the credits deny file's lines: a header {@code # cinefilm-deny <manifest date> <count>}, then one entry per
 * line ({@code film <id>}, {@code unit <unit key>}, {@code file <media_file_id>}, {@code edition <id>}). Blank lines
 * and further {@code #} lines are ignored. Pure: it takes lines, not a path, and never logs an entry.
 */
public final class CreditsDenyFile {

    private static final String HEADER = "# cinefilm-deny ";

    private CreditsDenyFile() {
    }

    /**
     * What the deny file lists.
     *
     * @param films film ids (test-side films)
     * @param units unit keys
     * @param files media file ids
     * @param editions edition ids
     */
    public record DenyList(Set<Long> films, Set<String> units, Set<Long> files, Set<Long> editions) {

        /** Copies the sets. */
        public DenyList {
            films = Set.copyOf(films);
            units = Set.copyOf(units);
            files = Set.copyOf(files);
            editions = Set.copyOf(editions);
        }
    }

    /**
     * A well-formed file.
     *
     * @param manifestDate the header's manifest date, as written
     * @param count the header's entry count
     * @param list the entries
     */
    public record Parsed(String manifestDate, int count, DenyList list) {
    }

    /**
     * Parses the file's lines.
     *
     * @param lines the lines in order
     * @return the parsed file, or empty when it is malformed (no or wrong header, a count that differs from the
     *     entries, or any line that is not an entry, a blank or a comment)
     */
    public static Optional<Parsed> parse(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).startsWith(HEADER)) {
            return Optional.empty();
        }
        String[] header = lines.get(0).substring(HEADER.length()).split(" ", -1);
        if (header.length != 2 || header[0].isEmpty() || !isNumber(header[1], 9)) {
            return Optional.empty();
        }
        int count = Integer.parseInt(header[1]);
        Set<Long> films = new HashSet<>();
        Set<String> units = new HashSet<>();
        Set<Long> files = new HashSet<>();
        Set<Long> editions = new HashSet<>();
        int entries = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            if (space < 0) {
                return Optional.empty();
            }
            String kind = line.substring(0, space);
            String value = line.substring(space + 1);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            switch (kind) {
                case "unit" -> units.add(value);
                case "film", "file", "edition" -> {
                    if (!isNumber(value, 18)) {
                        return Optional.empty();
                    }
                    Set<Long> target = kind.equals("film") ? films : kind.equals("file") ? files : editions;
                    target.add(Long.parseLong(value));
                }
                default -> {
                    return Optional.empty();
                }
            }
            entries++;
        }
        if (entries != count) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(header[0], count, new DenyList(films, units, files, editions)));
    }

    private static boolean isNumber(String text, int maxDigits) {
        if (text.isEmpty() || text.length() > maxDigits) {
            return false;
        }
        return text.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
