package gregochr.cinefilm.ratings;

import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import java.time.Instant;
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

    /** Remembers an IMDb id found for a film that was stored before the id was kept. */
    @Transactional
    public void saveImdbId(Long filmId, String imdbId) {
        films.findById(filmId).ifPresent(f -> f.setImdbId(imdbId));
    }

    /**
     * Stores what OMDb said and stamps the film as fetched.
     *
     * @param filmId the film
     * @param ratings the ratings, or null when there is nothing to show (no IMDb id, or OMDb does not know the film);
     *     every rating field is then cleared
     * @param at the time to stamp
     */
    @Transactional
    public void saveRatings(Long filmId, OmdbRatings ratings, Instant at) {
        Film film = films.findById(filmId).orElse(null);
        if (film == null) {
            return;
        }
        film.setImdbRating(ratings == null ? null : ratings.imdbRating());
        film.setImdbVotes(ratings == null ? null : ratings.imdbVotes());
        film.setRtScore(ratings == null ? null : ratings.rtScore());
        film.setRtUrl(ratings == null ? null : ratings.rtUrl());
        film.setRatingsFetchedAt(at);
    }
}
