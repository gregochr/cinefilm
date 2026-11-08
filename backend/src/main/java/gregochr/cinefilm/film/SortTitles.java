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
        return key.startsWith(ARTICLE) && key.length() > ARTICLE.length()
            ? key.substring(ARTICLE.length()).stripLeading()
            : key;
    }
}
