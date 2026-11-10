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
     * Films whose last played edition is part-way through and has not been removed from Continue watching, most
     * recently played first. Removing only sets a flag on that edition's state; the position stays.
     */
    public static List<FilmView> continueWatching(List<FilmView> views) {
        return views.stream()
            .filter(v -> v.progress().isPresent())
            .filter(v -> !v.lastPlayedEdition().flatMap(v::state)
                .map(PlaybackState::isHiddenFromContinueWatching).orElse(false))
            .sorted(Comparator.comparing(FilmView::lastPlayedAt).reversed())
            .toList();
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

    /** Applies search, filters and sort from the library query. */
    public static List<FilmView> filterAndSort(List<FilmView> views, LibraryQuery query) {
        String show = query.show() == null ? "all" : query.show().toLowerCase(Locale.ROOT);
        if (!List.of("all", "unwatched", "watched").contains(show)) {
            throw new BadRequestException("show must be all, unwatched or watched");
        }
        Comparator<FilmView> order = comparator(query.sort());
        return views.stream()
            .filter(v -> matchesSearch(v, query.q()))
            .filter(v -> query.genre() == null || query.genre().isBlank() || hasGenre(v, query.genre()))
            .filter(v -> query.decade() == null || v.film().getYear() != null
                && v.film().getYear() / 10 * 10 == query.decade())
            .filter(v -> "all".equals(show) || v.watched() == "watched".equals(show))
            .sorted(order)
            .toList();
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
            : Stream.of(view.film().getCollection().getName().strip().replaceFirst("(?i)\\s+collection$", ""));
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
            case "rt" -> Comparator.comparing((FilmView v) -> v.film().getRtScore(),
                Comparator.nullsLast(Comparator.<Integer>reverseOrder())).thenComparing(byTitle);
            default -> throw new BadRequestException("sort must be title, year, added, imdb or rt");
        };
    }
}
