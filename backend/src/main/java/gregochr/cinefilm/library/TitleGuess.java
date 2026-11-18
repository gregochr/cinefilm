package gregochr.cinefilm.library;

/**
 * The title and year we search TMDB for.
 *
 * @param title the title to search for, without a trailing year
 * @param year the release year if one was found, else null
 */
public record TitleGuess(String title, Integer year) {

    private static final int YEAR_BRACKETS_LENGTH = 6;
    private static final String ASCII_WHITESPACE = " \t\n\u000B\f\r";
    private static final String LINE_TERMINATORS = "\n\r\u0085\u2028\u2029";
    private static final int FIRST_FILM_YEAR = 1888;
    private static final int LAST_YEAR = 2100;

    /**
     * Guesses from the embedded {@code title} tag if there is one, else the file name without its extension. A
     * trailing {@code (1999)} or {@code [1999]} is removed and used as the year; failing that the file name's year,
     * then the {@code date} tag's year.
     *
     * @param embeddedTitle the format {@code title} tag, or null
     * @param fileName the file name (no folders)
     * @param tagYear the year from the {@code date} tag, or null
     * @return the guess
     */
    public static TitleGuess of(String embeddedTitle, String fileName, Integer tagYear) {
        int dot = fileName.lastIndexOf('.');
        String stem = tidy(dot > 0 ? fileName.substring(0, dot) : fileName);
        boolean embedded = embeddedTitle != null && !embeddedTitle.isBlank();
        TitleGuess primary = split(embedded ? embeddedTitle.trim() : stem);
        Integer year = primary.year();
        if (year == null && embedded) {
            year = split(stem).year();
        }
        return new TitleGuess(primary.title(), year != null ? year : tagYear);
    }

    /**
     * Guesses as {@link #of(String, String, Integer)} does, but reads a file name through {@link FileNameParser}, so
     * an edition tag, a part suffix, an id tag or a scene-style name ("Rocky.IV.1985.1080p.BluRay.x264-SPARKS") never
     * reaches TMDB as part of the title. An embedded {@code title} tag still wins when there is one.
     *
     * @param embeddedTitle the format {@code title} tag, or null
     * @param fileName the file name (no folders)
     * @param tagYear the year from the {@code date} tag, or null
     * @param parsed what the path says
     * @return the guess
     */
    public static TitleGuess of(String embeddedTitle, String fileName, Integer tagYear,
                                FileNameParser.ParsedName parsed) {
        if (embeddedTitle != null && !embeddedTitle.isBlank()) {
            return of(embeddedTitle, fileName, tagYear);
        }
        return new TitleGuess(parsed.title(), parsed.year() != null ? parsed.year() : tagYear);
    }

    /**
     * Splits a title from a trailing {@code (1999)} or {@code [1999]}; the brackets need not match each other. Both
     * callers hand it text that is already trimmed, so nothing ASCII-white follows the closing bracket.
     */
    private static TitleGuess split(String text) {
        int end = text.length();
        int open = end - YEAR_BRACKETS_LENGTH;
        if (open >= 0 && isOpening(text.charAt(open)) && isClosing(text.charAt(end - 1))
            && areDigits(text, open + 1, end - 1)) {
            int titleEnd = open;
            while (titleEnd > 0 && isAsciiSpace(text.charAt(titleEnd - 1))) {
                titleEnd--;
            }
            String title = text.substring(0, titleEnd);
            int year = Integer.parseInt(text.substring(open + 1, end - 1));
            if (year >= FIRST_FILM_YEAR && year <= LAST_YEAR && !hasLineTerminator(title) && !title.isBlank()) {
                return new TitleGuess(title.trim(), year);
            }
        }
        return new TitleGuess(text, null);
    }

    private static boolean areDigits(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isOpening(char c) {
        return c == '(' || c == '[';
    }

    private static boolean isClosing(char c) {
        return c == ')' || c == ']';
    }

    /** Java's regex whitespace: ASCII space, tab, line feed, vertical tab, form feed and carriage return. */
    private static boolean isAsciiSpace(char c) {
        return ASCII_WHITESPACE.indexOf(c) >= 0;
    }

    /** What {@code .} never matched in the pattern this replaced. */
    private static boolean hasLineTerminator(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (LINE_TERMINATORS.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** A name with no spaces but dots or underscores ("The.Wicker.Man") reads as words. */
    private static String tidy(String name) {
        String trimmed = name.trim();
        return trimmed.contains(" ") ? trimmed : trimmed.replaceAll("[._]+", " ").trim();
    }
}
