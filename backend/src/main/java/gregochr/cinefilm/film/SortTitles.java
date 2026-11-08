package gregochr.cinefilm.film;

import java.util.Locale;

/** Builds the sort key for a title: lower-cased, leading "The " ignored. */
public final class SortTitles {

    private static final String ARTICLE = "the ";

    private SortTitles() {
    }

    /** Returns the sort key for {@code title}. */
    public static String of(String title) {
        String key = title.strip().toLowerCase(Locale.ROOT);
        // The key is stripped, so a key that starts with "the " always has more after it.
        return key.startsWith(ARTICLE)
            ? key.substring(ARTICLE.length()).stripLeading()
            : key;
    }
}
