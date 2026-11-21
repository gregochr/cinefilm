package gregochr.cinefilm.library;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmMatchFixed;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.library.AdminDtos.LeftUnmatchedDto;
import gregochr.cinefilm.library.AdminDtos.MatchApplied;
import gregochr.cinefilm.library.AdminDtos.MatchResultDto;
import gregochr.cinefilm.library.AdminDtos.MatchSearchDto;
import gregochr.cinefilm.search.LibraryChanged;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import gregochr.cinefilm.tmdb.TmdbModels.Credit;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fix match: the owner searches TMDB for a film that needs a match and picks the right one. Applying takes the
 * {@link LibraryLock} (a scan rewrites films too) and answers a 409 when it cannot; searching does not. Applying
 * settles the film ({@code editionsConfirmed}); if another film already holds the chosen TMDB id the owner has said
 * they are one film, so this film is joined into it by {@link EditionJoin} with no different-film guard.
 */
@Service
public class FixMatch {

    /** The most results the panel shows. */
    static final int MAX_RESULTS = 8;
    private static final int CAST_SHOWN = 4;
    private static final int SUMMARY_MAX = 500;
    private static final String TMDB_DOWN = "TMDB is not available right now";

    private final TmdbApi tmdb;
    private final FilmRepository films;
    private final FilmPreferenceRepository preferences;
    private final LibraryIngest ingest;
    private final EditionJoin join;
    private final GroupingChangeRepository changes;
    private final LibraryLock lock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * Creates the service over TMDB, the films, their preferences, the ingest (for {@code applyMovie}), the join and
     * the lock.
     */
    public FixMatch(TmdbApi tmdb, FilmRepository films, FilmPreferenceRepository preferences,
                    LibraryIngest ingest, EditionJoin join,
                    GroupingChangeRepository changes, LibraryLock lock, PlatformTransactionManager transactions,
                    Clock clock, ApplicationEventPublisher events) {
        this.tmdb = tmdb;
        this.films = films;
        this.preferences = preferences;
        this.ingest = ingest;
        this.join = join;
        this.changes = changes;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
        this.clock = clock;
        this.events = events;
    }

    /**
     * Searches TMDB and returns up to {@value #MAX_RESULTS} results with their details (fetched one by one through
     * TMDB's request pacer). A search only reads TMDB and writes nothing, so it does not take the library lock and
     * works during a scan.
     *
     * @param filmId the film being matched
     * @param query the title to search for
     * @param year the release year to narrow by, or null
     * @return the results in TMDB's order, the file's length, and the result to pick first, if any (see
     *     {@link #suggest})
     * @throws BadRequestException when the query is blank
     * @throws NotFoundException when there is no such film
     * @throws ServiceUnavailableException when TMDB cannot answer
     */
    public MatchSearchDto search(Long filmId, String query, Integer year) {
        if (query == null || query.isBlank()) {
            throw new BadRequestException("A title to search for is needed");
        }
        FilmFacts facts = readTx.execute(status -> facts(filmId));
        try {
            List<MatchResultDto> results = new ArrayList<>();
            for (SearchHit hit : tmdb.search(query.strip(), year)) {
                if (results.size() == MAX_RESULTS) {
                    break;
                }
                tmdb.movie(hit.id()).ifPresent(movie -> results.add(result(movie)));
            }
            return suggest(facts, results);
        } catch (TmdbUnavailableException e) {
            throw new ServiceUnavailableException(TMDB_DOWN, e);
        }
    }

    /**
     * Chooses the result to pick first. If the file's length is known and exactly one result matches it, that one
     * ("runtime"). Otherwise the scan's best stored candidate, when it is among the results and the length does not
     * contradict it ("guess"). Otherwise nothing.
     */
    static MatchSearchDto suggest(FilmFacts facts, List<MatchResultDto> results) {
        Long fileMs = facts.fileRuntimeMs();
        long length = fileMs == null ? 0 : fileMs;
        List<MatchResultDto> sameLength = results.stream()
            .filter(r -> TmdbMatcher.runtimeMatches(r.runtimeMinutes(), length)).toList();
        if (sameLength.size() == 1) {
            return new MatchSearchDto(fileMs, sameLength.get(0).tmdbId(), "runtime", results);
        }
        Long guessId = facts.guessTmdbId();
        if (guessId != null) {
            MatchResultDto guess = results.stream().filter(r -> r.tmdbId() == guessId).findFirst().orElse(null);
            if (guess != null && (length <= 0 || guess.runtimeMinutes() == null
                || TmdbMatcher.runtimeMatches(guess.runtimeMinutes(), length))) {
                return new MatchSearchDto(fileMs, guessId, "guess", results);
            }
        }
        return new MatchSearchDto(fileMs, null, null, results);
    }

    /** What search needs from the film, read in one transaction: the default edition's length and the best guess. */
    record FilmFacts(Long fileRuntimeMs, Long guessTmdbId) {
    }

    private FilmFacts facts(Long filmId) {
        Film film = films.findById(filmId).orElseThrow(() -> new NotFoundException("No film " + filmId));
        Long stored = preferences.findById(filmId).map(FilmPreference::getDefaultEditionId).orElse(null);
        Long runtime = DefaultEdition.current(film.getEditions(), stored).map(Edition::getRuntimeMs)
            .filter(ms -> ms > 0).orElse(null);
        Long guess = StoredCandidates.of(film).stream().findFirst().map(Candidate::tmdbId).orElse(null);
        return new FilmFacts(runtime, guess);
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
            MatchApplied applied = tx.execute(status -> settle(filmId, movie));
            events.publishEvent(new LibraryChanged());
            events.publishEvent(new FilmMatchFixed());
            return applied;
        });
    }

    /**
     * The owner chooses to leave a film without a TMDB match: it is marked {@code LEFT_UNMATCHED} and its stored
     * candidates are cleared, so To do stops listing it and no scan retries it. Fix match (by id) and a Plex-supplied
     * TMDB id can still match it.
     * Only a film To do lists as needing a match may be left; takes the library lock like {@link #apply}.
     *
     * @param filmId the film to leave unmatched
     * @throws NotFoundException when there is no such film
     * @throws ConflictException when the film is not waiting for a match, or a scan, import or another match holds
     *     the library lock
     */
    public void leaveUnmatched(Long filmId) {
        changeUnderLock(filmId, Film::needsMatch, "This film is not waiting for a match", film -> {
            film.setMatchStatus(MatchStatus.LEFT_UNMATCHED);
            film.setMatchCandidates(null);
        });
    }

    /**
     * The way back from {@link #leaveUnmatched}: a film left unmatched is marked {@code NEEDS_MATCH} again, so To do
     * lists it. Only a {@code LEFT_UNMATCHED} film may be put back; takes the library lock like {@link #apply}.
     *
     * @param filmId the film to put back
     * @throws NotFoundException when there is no such film
     * @throws ConflictException when the film is not left unmatched, or a scan, import or another match holds the
     *     library lock
     */
    public void putBack(Long filmId) {
        changeUnderLock(filmId, film -> film.getMatchStatus() == MatchStatus.LEFT_UNMATCHED,
            "This film is not left unmatched", film -> film.setMatchStatus(MatchStatus.NEEDS_MATCH));
    }

    /**
     * The films the owner left unmatched, by title. Reads only, so it takes no lock.
     *
     * @return one entry per film, sorted by title
     */
    public List<LeftUnmatchedDto> leftUnmatched() {
        return readTx.execute(status -> films.findByMatchStatusOrderBySortTitle(MatchStatus.LEFT_UNMATCHED).stream()
            .map(f -> new LeftUnmatchedDto(f.getId(), f.getTitle(), f.getYear())).toList());
    }

    /** Takes the library lock, then changes one film in a transaction if it passes the guard. */
    private void changeUnderLock(Long filmId, Predicate<Film> allowed, String refusal, Consumer<Film> change) {
        locked(() -> {
            tx.executeWithoutResult(status -> {
                Film film = films.findById(filmId).orElseThrow(() -> new NotFoundException("No film " + filmId));
                if (!allowed.test(film)) {
                    throw new ConflictException(refusal);
                }
                change.accept(film);
                films.save(film);
            });
            return null;
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
            throw new ConflictException(lock.busyText());
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
