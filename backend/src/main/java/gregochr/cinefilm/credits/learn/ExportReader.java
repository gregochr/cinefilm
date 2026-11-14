package gregochr.cinefilm.credits.learn;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads the export CSV into rows, checking the header and every value. */
final class ExportReader {

    /** The columns, in the order the export writes them. */
    static final List<String> COLUMNS = List.of("media_file_id", "path", "fingerprint", "duration_ms", "edition_id",
        "is_last_original_part", "film_id", "film_tmdb_id", "film_title", "film_year", "collection_id",
        "collection_tmdb_id", "plex_start_ms", "plex_fingerprint", "presence");

    private ExportReader() {
    }

    /**
     * Parses the export.
     *
     * @param text the CSV: a header, then one row per file
     * @return the rows, in file order
     * @throws IllegalArgumentException when the header differs, a row is malformed, or a file id repeats
     */
    static List<ExportRow> parse(String text) {
        List<List<String>> table = CsvReader.parse(text);
        if (table.isEmpty() || !table.get(0).equals(COLUMNS)) {
            throw new IllegalArgumentException("export header must be " + String.join(",", COLUMNS));
        }
        List<ExportRow> rows = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int line = 1; line < table.size(); line++) {
            List<String> f = table.get(line);
            if (f.size() != COLUMNS.size()) {
                throw new IllegalArgumentException("export row " + line + " has " + f.size() + " fields, not "
                    + COLUMNS.size());
            }
            ExportRow row = new ExportRow(requiredLong(f, 0, line), f.get(1), f.get(2), requiredLong(f, 3, line),
                requiredLong(f, 4, line), bool(f, 5, line), requiredLong(f, 6, line), optionalLong(f, 7, line),
                f.get(8), optionalInt(f, 9, line), optionalLong(f, 10, line), optionalLong(f, 11, line),
                requiredLong(f, 12, line), f.get(13), f.get(14));
            if (!seen.add(row.mediaFileId())) {
                throw new IllegalArgumentException("export row " + line + " repeats media_file_id "
                    + row.mediaFileId());
            }
            rows.add(row);
        }
        return rows;
    }

    private static long requiredLong(List<String> fields, int column, int line) {
        Long value = optionalLong(fields, column, line);
        if (value == null) {
            throw new IllegalArgumentException("export row " + line + ": " + COLUMNS.get(column) + " is empty");
        }
        return value;
    }

    private static Long optionalLong(List<String> fields, int column, int line) {
        String text = fields.get(column);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("export row " + line + ": " + COLUMNS.get(column)
                + " is not a number", e);
        }
    }

    private static Integer optionalInt(List<String> fields, int column, int line) {
        Long value = optionalLong(fields, column, line);
        try {
            return value == null ? null : Math.toIntExact(value);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("export row " + line + ": " + COLUMNS.get(column)
                + " is not a number", e);
        }
    }

    private static Boolean bool(List<String> fields, int column, int line) {
        String text = fields.get(column);
        return switch (text) {
            case "t" -> Boolean.TRUE;
            case "f" -> Boolean.FALSE;
            case "" -> null;
            default -> throw new IllegalArgumentException("export row " + line + ": " + COLUMNS.get(column)
                + " must be t or f");
        };
    }
}
