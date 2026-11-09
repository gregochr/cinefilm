package gregochr.cinefilm.ratings;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The database side of a ratings refresh: one short transaction per write, so no transaction spans an HTTP call. */
@Component
public class RatingsStore {

    private final FilmRepository films;

    /** Creates the store. */
    public RatingsStore(FilmRepository films) {
        this.films = films;
    }

    /**
     * What the refresh needs to know about a film.
     *
     * @param imdbId the stored IMDb id, or null
     * @param tmdbId the TMDB id, or null
     */
    public record Target(String imdbId, Long tmdbId) {
    }

    /** The film's ids, or empty when it has gone. */
    @Transactional(readOnly = true)
    public Optional<Target> target(Long filmId) {
        return films.findById(filmId).map(f -> new Target(f.getImdbId(), f.getTmdbId()));
    }

    /**
     * Remembers an IMDb id found for a film that was stored before the id was kept. The film is locked and the id is
     * only set if it is still missing, so an id a concurrent scan just wrote is never overwritten.
     *
     * @return true when the id was stored; false when the film has gone or already has an id
     */
    @Transactional
    public boolean saveImdbId(Long filmId, String imdbId) {
        Film film = films.findByIdForUpdate(filmId).orElse(null);
        if (film == null || film.getImdbId() != null) {
            return false;
        }
        film.setImdbId(imdbId);
        return true;
    }

    /**
     * Stores what OMDb said and stamps the film as fetched.
     *
     * @param filmId the film
     * @param expectedImdbId the IMDb id the refresh acted on (null when the film had none); if the film's id has
     *     changed since (a scan found one meanwhile), the result is stale and is discarded without stamping
     * @param ratings the ratings, or null when there is nothing to show (no IMDb id, or OMDb does not know the film);
     *     every rating field is then cleared
     * @param at the time to stamp
     * @return true when saved; false when the film has gone or its identity changed
     */
    @Transactional
    public boolean saveRatings(Long filmId, String expectedImdbId, OmdbRatings ratings, Instant at) {
        Film film = films.findByIdForUpdate(filmId).orElse(null);
        if (film == null || !Objects.equals(film.getImdbId(), expectedImdbId)) {
            return false;
        }
        film.setImdbRating(ratings == null ? null : ratings.imdbRating());
        film.setImdbVotes(ratings == null ? null : ratings.imdbVotes());
        film.setRtScore(ratings == null ? null : ratings.rtScore());
        film.setRtUrl(ratings == null ? null : ratings.rtUrl());
        film.setRatingsFetchedAt(at);
        return true;
    }
}
