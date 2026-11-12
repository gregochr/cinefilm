package gregochr.cinefilm.opensubtitles;

/**
 * What CineFilm asks of OpenSubtitles: search only (no login, no download). An outage is an
 * {@link OpenSubtitlesUnavailableException}, never a half answer and never "no results".
 */
public interface OpenSubtitlesApi {

    /** True when an API key is configured, so searches can be attempted. */
    boolean enabled();

    /**
     * Searches for English foreign-parts-only subtitles of a film.
     *
     * @param imdbId the IMDb id, for example {@code tt0936501}
     * @return how many usable results there are and the best of them
     * @throws IllegalArgumentException when the id is not {@code tt} and 1 to 10 digits
     * @throws OpenSubtitlesUnavailableException when OpenSubtitles cannot answer or no key is configured
     */
    OpenSubtitlesSearch searchForced(String imdbId);
}
