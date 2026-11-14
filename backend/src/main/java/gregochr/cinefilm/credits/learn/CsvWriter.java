package gregochr.cinefilm.credits.learn;

import java.util.List;

/**
 * Writes CSV as RFC 4180 asks: a field holding a comma, a double quote, a carriage return or a line feed is wrapped in
 * double quotes with each quote doubled; every row ends in a line feed. {@link CsvReader} reads it back.
 */
final class CsvWriter {

    private CsvWriter() {
    }

    /**
     * One row, with its line feed.
     *
     * @param fields the fields; a null is written as an empty field
     * @return the row
     */
    static String row(List<String> fields) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(field(fields.get(i)));
        }
        return row.append('\n').toString();
    }

    private static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
