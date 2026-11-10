package gregochr.cinefilm.ratings;

/** What CineFilm asks of OMDb. An outage is an {@link OmdbUnavailableException}, never a half answer. */
public interface OmdbApi {

    /** True when an API key is configured, so lookups can be attempted. */
    boolean enabled();

    /**
     * Looks a film up by IMDb id.
     *
     * @param imdbId the IMDb id, for example {@code tt0113277}
     * @return what OMDb said: the ratings, "unknown film", or "limit reached" (also for a refused key)
     * @throws OmdbUnavailableException when OMDb cannot answer or no key is configured
     */
    OmdbResult lookup(String imdbId);
}
