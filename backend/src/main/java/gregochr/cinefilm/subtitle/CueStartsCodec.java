package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores a sorted list of cue starts compactly as text: the first start, then the gap to each next one, in
 * milliseconds, comma-separated ({@code 12000,350,2210}). A full track of a film is a few thousand cues, so the text is
 * a few tens of KB. Plain text (not a {@code bigint[]}) so the same column works on PostgreSQL and on the H2 the tests
 * run on, with no custom Hibernate type. Pure.
 */
final class CueStartsCodec {

    private CueStartsCodec() {
    }

    /**
     * Encodes sorted starts.
     *
     * @param starts cue starts in milliseconds, in ascending order
     * @return the text; empty for no starts
     */
    static String encode(List<Long> starts) {
        StringBuilder text = new StringBuilder();
        long previous = 0;
        for (int i = 0; i < starts.size(); i++) {
            long start = starts.get(i);
            text.append(i == 0 ? "" : ",").append(start - previous);
            previous = start;
        }
        return text.toString();
    }

    /**
     * Decodes what {@link #encode} wrote.
     *
     * @param text the stored text
     * @return the starts in ascending order
     * @throws IllegalArgumentException when the text is not a comma-separated list of whole numbers
     */
    static List<Long> decode(String text) {
        List<Long> starts = new ArrayList<>();
        if (text.isEmpty()) {
            return starts;
        }
        long running = 0;
        for (String gap : text.split(",", -1)) {
            try {
                running += Long.parseLong(gap);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Stored cue starts are not numbers", e);
            }
            starts.add(running);
        }
        return starts;
    }
}
