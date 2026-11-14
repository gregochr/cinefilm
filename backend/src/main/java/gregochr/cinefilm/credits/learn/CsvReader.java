package gregochr.cinefilm.credits.learn;

import java.util.ArrayList;
import java.util.List;

/** A small RFC 4180 reader: quoted fields, doubled quotes, commas and line breaks inside quotes, LF or CRLF rows. */
final class CsvReader {

    private CsvReader() {
    }

    /**
     * Splits CSV text into rows of fields. A row with a single empty field (a blank line) is dropped, so a trailing
     * newline, or none, makes no difference, and CRLF is two row ends with a blank row between that is dropped.
     *
     * @param text the whole file
     * @return the rows, in order
     * @throws IllegalArgumentException when a quote is never closed, or a quote follows other text in a field
     */
    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < length && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    if (i + 1 < length && "\r\n,".indexOf(text.charAt(i + 1)) < 0) {
                        throw new IllegalArgumentException("CSV has text after a closing quote");
                    }
                    quoted = false;
                }
            } else if (c == '"') {
                if (field.length() > 0) {
                    throw new IllegalArgumentException("CSV has a quote in the middle of a field");
                }
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                endRow(rows, row, field);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV ends inside a quoted field");
        }
        endRow(rows, row, field);
        return rows;
    }

    private static void endRow(List<List<String>> rows, List<String> row, StringBuilder field) {
        row.add(field.toString());
        field.setLength(0);
        if (row.size() > 1 || !row.get(0).isEmpty()) {
            rows.add(row);
        }
    }
}
