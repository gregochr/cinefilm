package gregochr.cinefilm.library;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title and year we search TMDB for.
 *
 * @param title the title to search for, without a trailing year
 * @param year the release year if one was found, else null
 */
public record TitleGuess(String title, Integer year) {

    private static final Pattern TRAILING_YEAR = Pattern.compile("^(.*?)\\s*[(\\[](\\d{4})[)\\]]\\s*$");
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

    private static TitleGuess split(String text) {
        Matcher matcher = TRAILING_YEAR.matcher(text);
        if (matcher.matches()) {
            int year = Integer.parseInt(matcher.group(2));
            if (year >= FIRST_FILM_YEAR && year <= LAST_YEAR && !matcher.group(1).isBlank()) {
                return new TitleGuess(matcher.group(1).trim(), year);
            }
        }
        return new TitleGuess(text, null);
    }

    /** A name with no spaces but dots or underscores ("The.Wicker.Man") reads as words. */
    private static String tidy(String name) {
        String trimmed = name.trim();
        return trimmed.contains(" ") ? trimmed : trimmed.replaceAll("[._]+", " ").trim();
    }
}
