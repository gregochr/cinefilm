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
}
