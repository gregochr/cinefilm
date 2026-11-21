package gregochr.cinefilm.christmas;

import gregochr.cinefilm.christmas.ChristmasDtos.ChristmasView;
import gregochr.cinefilm.christmas.ChristmasDtos.FilmState;
import gregochr.cinefilm.christmas.ChristmasDtos.Item;
import gregochr.cinefilm.christmas.ChristmasDtos.Keywords;
import gregochr.cinefilm.christmas.ChristmasDtos.LeftOutItem;
import gregochr.cinefilm.christmas.ChristmasDtos.Limits;
import gregochr.cinefilm.christmas.ChristmasDtos.Reason;
import gregochr.cinefilm.christmas.ChristmasDtos.State;
import gregochr.cinefilm.christmas.ChristmasDtos.WeekDto;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.ChristmasChoice;
import gregochr.cinefilm.film.ChristmasOverrides;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.HolidayWeeks;
import gregochr.cinefilm.film.KeywordsRefresher;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.film.WeeklyThemeSelector;
import gregochr.cinefilm.library.LibraryLock;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Christmas films Admin screen: which films Home's Christmas week counts, and the owner's say over them. The list
 * is built by {@link WeeklyThemeSelector#christmasFilms}, the code Home uses, so the screen and Home cannot disagree
 * (Home shows the first {@link WeeklyThemeSelector#MAX_FILMS}). Writes one at a time, so two quick changes to one
 * film are last-write-wins on its one row.
 */
@Service
public class ChristmasFilms {

    /** What a change says while a scan runs (the owner can try again when it ends). */
    public static final String SCAN_RUNNING = "A scan is running — try again when it finishes";

    /** The most film ids {@link #states} answers for in one call. */
    public static final int MAX_STATES = 50;

    private final LibraryLoader loader;
    private final ChristmasOverrides overrides;
    private final FilmRepository films;
    private final KeywordsRefresher keywords;
    private final LibraryLock lock;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final ReentrantLock writes = new ReentrantLock();

    /** Creates the service over the library, the overrides, the keyword backfill, the library lock and the clock. */
    public ChristmasFilms(LibraryLoader loader, ChristmasOverrides overrides, FilmRepository films,
                          KeywordsRefresher keywords, LibraryLock lock, Clock clock,
                          PlatformTransactionManager transactions) {
        this.loader = loader;
        this.overrides = overrides;
        this.films = films;
        this.keywords = keywords;
        this.lock = lock;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactions);
    }

    /** The screen as it stands now. */
    public ChristmasView view() {
        return readTx.execute(status -> build());
    }

    /**
     * Sets, changes or (with {@code null}) forgets the owner's choice for a film, then deletes INCLUDE rows for films
     * TMDB tags anyway, and returns the screen.
     *
     * @throws ConflictException when a scan is running; nothing changes
     * @throws NotFoundException when no film in the library has that TMDB id
     */
    public ChristmasView choose(long tmdbId, ChristmasChoice choice) {
        // Lock order: this service's own write lock first (waited for, but only ever held for one short write), then
        // the shared library lock (never waited for: tried once, so nothing can deadlock). A scan that starts later
        // is refused by the library lock while this write runs, and a scan already running refuses this change.
        writes.lock();
        try {
            if (!lock.tryAcquire(LibraryLock.CHRISTMAS)) {
                throw new ConflictException(lock.heldBy(LibraryLock.SCAN) ? SCAN_RUNNING : lock.busyText());
            }
            try {
                write(tmdbId, choice);
            } finally {
                lock.release();
            }
            // Built after the library lock is given back, so a scan or import is not refused while the library loads.
            return view();
        } finally {
            writes.unlock();
        }
    }

    private void write(long tmdbId, ChristmasChoice choice) {
        // Browsable only, like Home and the lists: a hidden film cannot show, so there is nothing to override.
        boolean known = Boolean.TRUE.equals(readTx.execute(status ->
            films.findByTmdbIdOrderByIdAsc(tmdbId).stream().anyMatch(Film::browsable)));
        if (!known) {
            throw new NotFoundException("No film with TMDB id " + tmdbId);
        }
        writeTx.executeWithoutResult(status -> {
            if (choice == null) {
                overrides.remove(tmdbId);
            } else {
                overrides.set(tmdbId, choice);
            }
            dropRedundantIncludes();
        });
    }

    /**
     * Each film's Christmas state, in the order asked; ids that match no film are left out.
     *
     * @throws BadRequestException when more than {@link #MAX_STATES} ids are asked for, or an id is blank
     */
    public List<FilmState> states(List<Long> filmIds) {
        if (filmIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BadRequestException("Each film id must be a number");
        }
        Set<Long> ids = new LinkedHashSet<>(filmIds);
        if (ids.size() > MAX_STATES) {
            throw new BadRequestException("Ask for at most " + MAX_STATES + " films at a time");
        }
        return readTx.execute(status -> {
            ChristmasOverrides.Snapshot choices = overrides.snapshot();
            Map<Long, Film> byId = new HashMap<>();
            films.findAllById(ids).stream().filter(Film::browsable).forEach(f -> byId.put(f.getId(), f));
            return ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(f -> new FilmState(f.getId(), f.getTmdbId(),
                    stateOf(f, choices.include(), choices.exclude()))).toList();
        });
    }

    /** Membership is {@link WeeklyThemeSelector#isChristmas}, the rule Home uses; this only words the answer. */
    private static State stateOf(Film film, Set<Long> include, Set<Long> exclude) {
        if (WeeklyThemeSelector.isChristmas(film, include, exclude)) {
            return tagged(film) ? State.IN_KEYWORD : State.IN_ADDED;
        }
        Long tmdbId = film.getTmdbId();
        if (tmdbId == null) {
            return State.NO_TMDB_ID;
        }
        if (exclude.contains(tmdbId)) {
            return State.LEFT_OUT;
        }
        return film.getKeywordsFetchedAt() == null ? State.KEYWORDS_PENDING : State.NOT_IN;
    }

    /**
     * An INCLUDE row is redundant once TMDB tags the film: the keyword counts it, so the row goes. Two film rows can
     * share a TMDB id (a refused merge), and the keyword is fetched per row, so the row goes only when every film
     * holding the id carries the keyword; otherwise the untagged one still needs it.
     */
    private void dropRedundantIncludes() {
        Set<Long> included = overrides.snapshot().include();
        if (included.isEmpty()) {
            return;
        }
        films.findByTmdbIdIn(included).stream().collect(Collectors.groupingBy(Film::getTmdbId)).forEach(
            (id, rows) -> {
                if (rows.stream().allMatch(ChristmasFilms::tagged)) {
                    overrides.remove(id);
                }
            });
    }

    private ChristmasView build() {
        List<Film> library = loader.loadAll().stream().map(FilmView::film).toList();
        ChristmasOverrides.Snapshot choices = overrides.snapshot();
        Set<Long> exclude = choices.exclude();
        List<Item> inWeek = WeeklyThemeSelector.christmasFilms(library, choices.include(), exclude).stream()
            .map(f -> item(f, tagged(f) ? Reason.KEYWORD : Reason.ADDED)).toList();
        List<Item> added = inWeek.stream().filter(i -> i.reason() == Reason.ADDED).toList();
        List<LeftOutItem> leftOut = library.stream()
            .filter(f -> f.getTmdbId() != null && exclude.contains(f.getTmdbId()))
            .sorted(WeeklyThemeSelector.ROW_ORDER)
            .map(f -> new LeftOutItem(f.getId(), f.getTmdbId(), f.getTitle(), f.getYear(), f.getPosterPath(),
                tagged(f)))
            .toList();
        long fetched = films.countByTmdbIdIsNotNullAndKeywordsFetchedAtIsNotNull();
        long total = films.countByTmdbIdIsNotNull();
        boolean running = keywords.isRunning();
        boolean enabled = keywords.isEnabled();
        boolean incomplete = keywords.lastRunIncomplete();
        // Stalled: films are waiting, nothing is running, and nothing will fetch them (TMDB off, or the last run fell
        // short).
        boolean stalled = !running && fetched < total && (!enabled || incomplete);
        HolidayWeeks.Week week = HolidayWeeks.christmasWeekFrom(LocalDate.now(clock));
        return new ChristmasView(new WeekDto(week.start(), week.end()),
            new Limits(WeeklyThemeSelector.MAX_FILMS, WeeklyThemeSelector.MIN_FILMS),
            new Keywords(fetched, total, running, enabled, stalled),
            inWeek, added, leftOut, inWeek.size() < WeeklyThemeSelector.MIN_FILMS);
    }

    private static Item item(Film film, Reason reason) {
        return new Item(film.getId(), film.getTmdbId(), film.getTitle(), film.getYear(), film.getPosterPath(),
            reason);
    }

    private static boolean tagged(Film film) {
        return WeeklyThemeSelector.hasChristmasKeyword(film);
    }
}
