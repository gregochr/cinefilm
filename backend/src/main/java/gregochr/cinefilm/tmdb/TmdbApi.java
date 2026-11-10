package gregochr.cinefilm.tmdb;

import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.util.List;
import java.util.Optional;

/** What CineFilm asks of TMDB. A TMDB outage is a {@link TmdbUnavailableException}, never a half answer. */
public interface TmdbApi {

    /** True when a read access token is configured, so lookups can be attempted. */
    boolean enabled();

    /**
     * Searches films by title.
     *
     * @param title the title
     * @param year the release year to filter by, or null
     * @return the hits in TMDB's order
     * @throws TmdbUnavailableException when TMDB cannot answer
     */
    List<SearchHit> search(String title, Integer year);

    /**
     * A film with its credits. Cached by id for the life of the process, so a rescan does not refetch.
     *
     * @param id TMDB id
     * @return the film, or empty when TMDB does not know the id
     * @throws TmdbUnavailableException when TMDB cannot answer
     */
    Optional<MovieDetails> movie(long id);

    /**
     * A collection with its parts. Cached by id.
     *
     * @param id TMDB collection id
     * @return the collection, or empty when TMDB does not know the id
     * @throws TmdbUnavailableException when TMDB cannot answer
     */
    Optional<CollectionDetails> collection(long id);

    /**
     * A film's IMDb id from {@code /3/movie/{id}/external_ids}, for films stored before the id was kept.
     *
     * @param id TMDB id
     * @return the IMDb id, or empty when TMDB does not know the film or has no IMDb id for it
     * @throws TmdbUnavailableException when TMDB cannot answer
     */
    Optional<String> imdbId(long id);

    /**
     * Finds the film TMDB holds for an IMDb id, from {@code /3/find/{id}?external_source=imdb_id}.
     *
     * @param imdbId the IMDb id (for example {@code tt0113277})
     * @return TMDB's movie id, or empty when TMDB has no film for it
     * @throws TmdbUnavailableException when TMDB cannot answer
     */
    Optional<Long> findMovieByImdbId(String imdbId);
}
