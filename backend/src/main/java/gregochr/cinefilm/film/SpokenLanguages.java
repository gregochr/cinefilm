package gregochr.cinefilm.film;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * TMDB's spoken languages as stored on a film: lower-case ISO 639 codes, comma-separated, in TMDB's order. TMDB text
 * is untrusted, so a code is kept only when it is two or three letters; anything else is dropped.
 */
public final class SpokenLanguages {

    /** The column's width. */
    public static final int MAX_LENGTH = 200;

    private static final Pattern CODE = Pattern.compile("^[a-z]{2,3}$");

    private SpokenLanguages() {
    }

    /** The code lower-cased, or null when it is not a language code. */
    public static String code(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.strip().toLowerCase(Locale.ROOT);
        return CODE.matcher(code).matches() ? code : null;
    }

    /** The valid codes of {@code raw}, lower-cased, duplicates dropped, in order (null entries are dropped). */
    public static List<String> codes(List<String> raw) {
        List<String> result = new ArrayList<>();
        for (String candidate : raw) {
            String code = code(candidate);
            if (code != null && !result.contains(code)) {
                result.add(code);
            }
        }
        return List.copyOf(result);
    }

    /**
     * The column value: the codes joined with commas, cut at a whole code to fit {@link #MAX_LENGTH}. An empty list
     * is the empty string ("TMDB lists none"), never null.
     */
    public static String store(List<String> codes) {
        StringBuilder out = new StringBuilder();
        for (String code : codes(codes)) {
            int needed = out.isEmpty() ? code.length() : out.length() + 1 + code.length();
            if (needed > MAX_LENGTH) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(code);
        }
        return out.toString();
    }

    /** The codes in a column value; null and the empty string are no codes. */
    public static List<String> parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return codes(List.of(stored.split(",")));
    }
}
