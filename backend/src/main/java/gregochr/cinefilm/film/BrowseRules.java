package gregochr.cinefilm.film;

import gregochr.cinefilm.config.BadRequestException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** The pure browse rules: Home rows, search, filters and sorting. */
public final class BrowseRules {

    /** "Recently added" window. */
    public static final Duration RECENT_WINDOW = Duration.ofDays(30);

    /** "Not watched in years" horizon, in years. */
    public static final int STALE_YEARS = 5;

    private BrowseRules() {
    }

    /** Films whose last played edition is part-way through, most recently played first. */
    public static List<FilmView> continueWatching(List<FilmView> views) {
        return views.stream()
            .filter(v -> v.progress().isPresent())
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
            .filter(v -> query.decade() == null || v.film().getYear() / 10 * 10 == query.decade())
            .filter(v -> query.collection() == null || (v.film().getCollection() != null
                && Objects.equals(v.film().getCollection().getId(), query.collection())))
            .filter(v -> "all".equals(show) || v.watched() == "watched".equals(show))
            .sorted(order)
            .toList();
    }

    /** Case-insensitive substring search over title, cast names and director names. */
    public static boolean matchesSearch(FilmView view, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String needle = q.strip().toLowerCase(Locale.ROOT);
        return Stream.concat(
                Stream.of(view.film().getTitle()),
                view.film().getCredits().stream().map(c -> c.getPerson().getName()))
            .anyMatch(s -> s.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static boolean hasGenre(FilmView view, String genre) {
        return view.film().getGenres().stream().anyMatch(g -> g.equalsIgnoreCase(genre.strip()));
    }

    private static Comparator<FilmView> comparator(String sort) {
        String key = sort == null || sort.isBlank() ? "added" : sort.toLowerCase(Locale.ROOT);
        Comparator<FilmView> byTitle = Comparator.comparing((FilmView v) -> v.film().getSortTitle())
            .thenComparing(v -> v.film().getYear());
        return switch (key) {
            case "title" -> byTitle;
            case "year" -> Comparator.comparingInt((FilmView v) -> v.film().getYear()).thenComparing(byTitle);
            case "added" -> Comparator.comparing((FilmView v) -> v.film().getAddedAt()).reversed()
                .thenComparing(byTitle);
            default -> throw new BadRequestException("sort must be title, year or added");
        };
    }
}
