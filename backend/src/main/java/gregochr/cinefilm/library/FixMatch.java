package gregochr.cinefilm.library;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.library.AdminDtos.MatchApplied;
import gregochr.cinefilm.library.AdminDtos.MatchResultDto;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.Credit;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fix match: the owner searches TMDB for a film that needs a match and picks the right one. Both steps take the
 * {@link LibraryLock} (a scan rewrites films too) and answer a 409 when they cannot. Applying settles the film
 * ({@code editionsConfirmed}); if another film already holds the chosen TMDB id the owner has said they are one film,
 * so this film is joined into it by {@link EditionJoin} with no different-film guard.
 */
@Service
public class FixMatch {

    /** The most results the panel shows. */
    static final int MAX_RESULTS = 8;
    private static final int CAST_SHOWN = 4;
    private static final int SUMMARY_MAX = 500;
    private static final String BUSY = "A scan is running";
    private static final String TMDB_DOWN = "TMDB is not available right now";

    private final TmdbApi tmdb;
    private final FilmRepository films;
    private final LibraryIngest ingest;
    private final EditionJoin join;
    private final GroupingChangeRepository changes;
    private final LibraryLock lock;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** Creates the service over TMDB, the films, the ingest (for {@code applyMovie}), the join and the lock. */
    public FixMatch(TmdbApi tmdb, FilmRepository films, LibraryIngest ingest, EditionJoin join,
                    GroupingChangeRepository changes, LibraryLock lock, PlatformTransactionManager transactions,
                    Clock clock) {
        this.tmdb = tmdb;
        this.films = films;
        this.ingest = ingest;
        this.join = join;
        this.changes = changes;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * Searches TMDB and returns up to {@value #MAX_RESULTS} results with their details (fetched one by one through
     * TMDB's request pacer).
     *
     * @param filmId the film being matched
     * @param query the title to search for
     * @param year the release year to narrow by, or null
     * @return the results in TMDB's order
     * @throws BadRequestException when the query is blank
     * @throws NotFoundException when there is no such film
     * @throws ConflictException when a scan, import or another match holds the library lock
     * @throws ServiceUnavailableException when TMDB cannot answer
     */
    public List<MatchResultDto> search(Long filmId, String query, Integer year) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("A title to search for is needed");
        }
        return locked(() -> {
            requireFilm(filmId);
            try {
                List<MatchResultDto> results = new ArrayList<>();
                for (SearchHit hit : tmdb.search(query.strip(), year)) {
                    if (results.size() == MAX_RESULTS) {
                        break;
                    }
                    tmdb.movie(hit.id()).ifPresent(movie -> results.add(result(movie)));
                }
                return results;
            } catch (TmdbUnavailableException e) {
                throw new ServiceUnavailableException(TMDB_DOWN, e);
            }
        });
    }

    /**
     * Applies the chosen TMDB film to the film and settles it. If another film already has that TMDB id this one is
     * joined into it.
     *
     * @param filmId the film being matched
     * @param tmdbId the TMDB film the owner picked
     * @return the film that holds the files now, and whether it was a join
     * @throws BadRequestException when no TMDB id is given
     * @throws NotFoundException when there is no such film, or TMDB does not know the id
     * @throws ConflictException when a scan, import or another match holds the library lock
     * @throws ServiceUnavailableException when TMDB cannot answer
     */
    public MatchApplied apply(Long filmId, Long tmdbId) {
        if (tmdbId == null) {
            throw new BadRequestException("A TMDB id is needed");
        }
        return locked(() -> {
            requireFilm(filmId);
            MovieDetails movie;
            try {
                movie = tmdb.movie(tmdbId).orElseThrow(() -> new NotFoundException("TMDB has no film " + tmdbId));
            } catch (TmdbUnavailableException e) {
                throw new ServiceUnavailableException(TMDB_DOWN, e);
            }
            return tx.execute(status -> settle(filmId, movie));
        });
    }

    private MatchApplied settle(Long filmId, MovieDetails movie) {
        Film film = films.findById(filmId).orElseThrow(() -> new NotFoundException("No film " + filmId));
        Film existing = films.findByTmdbIdOrderByIdAsc(movie.id()).stream()
            .filter(f -> !f.getId().equals(filmId)).findFirst().orElse(null);
        if (existing == null) {
            ingest.applyMovie(film, movie);
            film.setEditionsConfirmed(true);
            films.save(film);
            return new MatchApplied(filmId, false);
        }
        String from = film.getTitle();
        Long target = existing.getId();
        join.joinFilms(filmId, target);
        // The merge cleared the persistence context: settle the survivor from a fresh read.
        Film survivor = films.findById(target).orElseThrow();
        survivor.setEditionsConfirmed(true);
        films.save(survivor);
        String summary = "You joined \"" + from + "\" into \"" + survivor.getTitle() + "\" with Fix match";
        changes.save(new GroupingChange(clock.instant(), GroupingChange.JOIN,
            summary.substring(0, Math.min(summary.length(), SUMMARY_MAX))));
        return new MatchApplied(target, true);
    }

    private void requireFilm(Long filmId) {
        if (!films.existsById(filmId)) {
            throw new NotFoundException("No film " + filmId);
        }
    }

    private <T> T locked(Supplier<T> work) {
        if (!lock.tryAcquire(LibraryLock.MATCH)) {
            throw new ConflictException(BUSY);
        }
        try {
            return work.get();
        } finally {
            lock.release();
        }
    }

    private static MatchResultDto result(MovieDetails movie) {
        String director = movie.directors().isEmpty() ? null
            : String.join(", ", movie.directors().stream().map(Credit::name).toList());
        List<String> cast = movie.cast().stream().limit(CAST_SHOWN).map(Credit::name).toList();
        return new MatchResultDto(movie.id(), movie.title(), movie.year(), movie.runtimeMinutes(),
            movie.posterPath(), director, cast);
    }
}
