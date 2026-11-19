package gregochr.cinefilm.film;

/** What the owner decided about a film for Christmas week. */
public enum ChristmasChoice {
    /** Show the film although TMDB's {@code christmas} keyword misses it. */
    INCLUDE,
    /** Hide the film although the keyword catches it. */
    EXCLUDE
}
