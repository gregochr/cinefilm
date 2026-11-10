package gregochr.cinefilm.film;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Search text rules, mirrored by {@code normalise} in {@code frontend/src/state/search.js} (the shared cases are in
 * {@code docs/design/search/vectors.json}): NFD, combining marks stripped, lower-cased, apostrophes removed, every
 * other non-letter/digit a space, spaces collapsed. A query matches a name when every one of its words is a prefix
 * of a different word of the name.
 */
public final class SearchText {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’‘]");

    private SearchText() {
    }

    /** The normalised words of {@code s}; empty for null or text with no letters or digits. */
    public static List<String> words(String s) {
        if (s == null) {
            return List.of();
        }
        String text = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("")
            .toLowerCase(Locale.ROOT);
        text = APOSTROPHES.matcher(text).replaceAll("");
        text = SEPARATORS.matcher(text).replaceAll(" ").strip();
        return text.isEmpty() ? List.of() : List.of(text.split(" "));
    }

    /** True when every term is a prefix of some word, each word used by one term only. */
    public static boolean allPrefix(List<String> terms, List<String> words) {
        return assign(terms, 0, words, new boolean[words.size()]);
    }

    /** True when {@code q} has words and each is a prefix of a different word of {@code name}. */
    public static boolean matches(String q, String name) {
        List<String> terms = words(q);
        return !terms.isEmpty() && allPrefix(terms, words(name));
    }

    private static boolean assign(List<String> terms, int at, List<String> words, boolean[] used) {
        if (at == terms.size()) {
            return true;
        }
        for (int i = 0; i < words.size(); i++) {
            if (!used[i] && words.get(i).startsWith(terms.get(at))) {
                used[i] = true;
                if (assign(terms, at + 1, words, used)) {
                    return true;
                }
                used[i] = false;
            }
        }
        return false;
    }
}
