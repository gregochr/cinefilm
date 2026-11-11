package gregochr.cinefilm.film;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.playback.PlaybackState;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** The pure browse rules: Home rows, search, filters and sorting. */
public final class BrowseRules {

    /** "Recently added" window. */
    public static final Duration RECENT_WINDOW = Duration.ofDays(30);

    /** "Not watched in years" horizon, in years. */
    public static final int STALE_YEARS = 5;

    private BrowseRules() {
    }

    /**
     * Whether the film is in progress: its last played edition has a position of at least {@code minMs} (and at
     * least 1 ms, so a minimum of 0 still needs some progress). The watched flag is deliberately not looked at: a
     * rewatch keeps {@code watched = true} while its position is above 0, and reaching the end or marking as watched
     * resets the position to 0.
     */
    public static boolean inProgress(FilmView view, long minMs) {
        return view.lastPlayedEdition().flatMap(view::state)
            .map(s -> s.getPositionMs() >= Math.max(minMs, 1)).orElse(false);
    }

    /**
     * Films in progress whose last played edition has not been removed from Continue watching, most recently
     * played first. Removing only sets a flag on that edition's state; the position stays.
     */
    public static List<FilmView> continueWatching(List<FilmView> views, long minMs) {
        return views.stream()
            .filter(v -> inProgress(v, minMs))
            .filter(v -> !hiddenFromContinueWatching(v))
            .sorted(Comparator.comparing(FilmView::lastPlayedAt).reversed())
            .toList();
    }

    /** Whether the film's last played edition has been removed from Continue watching. */
    public static boolean hiddenFromContinueWatching(FilmView view) {
        return view.lastPlayedEdition().flatMap(view::state)
            .map(PlaybackState::isHiddenFromContinueWatching).orElse(false);
    }

    /** Films added within the last 30 days, newest first. */
    public static List<FilmView> recentlyAdded(List<FilmView> views, Instant now) {
        Instant cutoff = now.minus(RECENT_WINDOW);
        return views.stream()
            .filter(v -> !v.film().getAddedAt().isBefore(cutoff))
            .sorted(Comparator.comparing((FilmView v) -> v.film().getAddedAt()).reversed())
            .toList();
    }

    /** Watched films whose last activity was more than five calendar years ago in {@code zone}, oldest first. */
    public static List<FilmView> notWatchedInYears(List<FilmView> views, Instant now, ZoneId zone) {
        Instant cutoff = now.atZone(zone).minusYears(STALE_YEARS).toInstant();
        return views.stream()
            .filter(FilmView::watched)
            .filter(v -> v.lastActivityAt() != null && v.lastActivityAt().isBefore(cutoff))
            .sorted(Comparator.comparing(FilmView::lastActivityAt))
            .toList();
    }

    /**
     * Applies search, filters and sort from the library query. {@code minMs} is the minimum watch time of the
     * in-progress rule. In progress, Unwatched and Watched never overlap and together cover every film: In progress
     * wins (a rewatch is in progress, hidden films included), Watched is "any edition watched and not in progress",
     * Unwatched is the rest.
     */
    public static List<FilmView> filterAndSort(List<FilmView> views, LibraryQuery query, long minMs) {
        String show = query.show() == null ? "all" : query.show().toLowerCase(Locale.ROOT);
        if (!List.of("all", "in-progress", "unwatched", "watched").contains(show)) {
            throw new BadRequestException("show must be all, in-progress, unwatched or watched");
        }
        Comparator<FilmView> order = comparator(query.sort());
        return views.stream()
            .filter(v -> matchesSearch(v, query.q()))
            .filter(v -> query.genre() == null || query.genre().isBlank() || hasGenre(v, query.genre()))
            .filter(v -> query.decade() == null || v.film().getYear() != null
                && v.film().getYear() / 10 * 10 == query.decade())
            .filter(v -> matchesShow(v, show, minMs))
            .sorted(order)
            .toList();
    }

    private static boolean matchesShow(FilmView view, String show, long minMs) {
        boolean inProgress = inProgress(view, minMs);
        return switch (show) {
            case "in-progress" -> inProgress;
            case "watched" -> !inProgress && view.watched();
            case "unwatched" -> !inProgress && !view.watched();
            default -> true;
        };
    }

    /**
     * Word-prefix search: every word of the query must be a prefix of a different word of the title, of one cast or
     * director name, or of the film's collection name (without a trailing " Collection"), ignoring case, accents and
     * punctuation. The same rule as the header search ({@link SearchText}).
     */
    public static boolean matchesSearch(FilmView view, String q) {
        List<String> terms = SearchText.words(q);
        if (terms.isEmpty()) {
            return true;
        }
        Stream<String> collection = view.film().getCollection() == null ? Stream.empty()
            : Stream.of(SearchText.withoutCollectionSuffix(view.film().getCollection().getName()));
        return Stream.of(Stream.of(view.film().getTitle()),
                view.film().getCredits().stream().map(c -> c.getPerson().getName()), collection)
            .flatMap(s -> s)
            .anyMatch(name -> SearchText.allPrefix(terms, SearchText.words(name)));
    }

    private static boolean hasGenre(FilmView view, String genre) {
        return view.film().getGenres().stream().anyMatch(g -> g.equalsIgnoreCase(genre.strip()));
    }

    /** Oldest first; a film with no known year goes last. */
    public static final Comparator<FilmView> BY_YEAR = Comparator.comparing((FilmView v) -> v.film().getYear(),
        Comparator.nullsLast(Comparator.<Integer>naturalOrder()));

    /** Newest first; a film with no known year goes last. */
    public static final Comparator<FilmView> NEWEST_FIRST = Comparator.comparing((FilmView v) -> v.film().getYear(),
        Comparator.nullsLast(Comparator.<Integer>reverseOrder()));

    private static Comparator<FilmView> comparator(String sort) {
        String key = sort == null || sort.isBlank() ? "added" : sort.toLowerCase(Locale.ROOT);
        Comparator<FilmView> byTitle = Comparator.comparing((FilmView v) -> v.film().getSortTitle())
            .thenComparing(BY_YEAR);
        return switch (key) {
            case "title" -> byTitle;
            case "year" -> BY_YEAR.thenComparing(byTitle);
            case "added" -> Comparator.comparing((FilmView v) -> v.film().getAddedAt()).reversed()
                .thenComparing(byTitle);
            case "imdb" -> Comparator.comparing((FilmView v) -> v.film().getImdbRating(),
                Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder())).thenComparing(byTitle);
            case "last-watched" -> Comparator.comparing(FilmView::lastPlayedAt,
                Comparator.nullsLast(Comparator.<Instant>reverseOrder())).thenComparing(byTitle);
            case "rt" -> Comparator.comparing((FilmView v) -> v.film().getRtScore(),
                Comparator.nullsLast(Comparator.<Integer>reverseOrder())).thenComparing(byTitle);
            default -> throw new BadRequestException("sort must be title, year, added, imdb, rt or last-watched");
        };
    }
}
