package gregochr.cinefilm.film;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Chooses the weekly theme (decision 4): a decade, a genre or a director with at least five films, picked
 * deterministically from the ISO week (Monday start) and the library contents; in Halloween and Christmas week,
 * that holiday's films.
 */
public final class WeeklyThemeSelector {

    /** Minimum number of films a decade, genre or director needs to become a theme. */
    public static final int MIN_FILMS = 5;

    /** Maximum number of films shown in the row. */
    public static final int MAX_FILMS = 12;

    private WeeklyThemeSelector() {
    }

    /** The week number the theme is keyed on, e.g. 202646 for ISO week 46 of 2026. Changes every Monday. */
    public static int weekKey(LocalDate date) {
        return date.get(IsoFields.WEEK_BASED_YEAR) * 100 + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Picks the theme for the ISO week containing {@code date}, with no Christmas overrides. */
    public static WeeklyTheme choose(LocalDate date, List<Film> library) {
        return choose(date, library, HolidayOverrides.NONE);
    }

    /**
     * Picks the theme for the ISO week containing {@code date}. In a holiday week ({@link HolidayWeeks}) the theme is
     * that holiday's films, unless fewer than {@link #MIN_FILMS} match, when the normal rotation applies.
     */
    public static WeeklyTheme choose(LocalDate date, List<Film> library, HolidayOverrides overrides) {
        Optional<WeeklyTheme> holiday = HolidayWeeks.holidayFor(date)
            .map(h -> holidayTheme(h, library, overrides))
            .filter(t -> t.films().size() >= MIN_FILMS);
        if (holiday.isPresent()) {
            return holiday.get();
        }
        List<Candidate> candidates = candidates(library);
        if (candidates.isEmpty()) {
            return new WeeklyTheme("library", "all", "This week: your library", WeeklyTheme.STANDING_CAPTION,
                pick(library, f -> true));
        }
        Candidate chosen = candidates.get((int) Math.floorMod(mix(weekKey(date)), candidates.size()));
        return new WeeklyTheme(chosen.kind, chosen.value, chosen.title, WeeklyTheme.STANDING_CAPTION,
            pick(library, chosen.matches));
    }

    private static WeeklyTheme holidayTheme(HolidayWeeks.Holiday holiday, List<Film> library,
                                            HolidayOverrides overrides) {
        return switch (holiday) {
            case HALLOWEEN -> new WeeklyTheme("holiday", "halloween", "This week: Halloween",
                WeeklyTheme.STANDING_CAPTION, pick(library, f -> f.getGenres().contains("Horror")));
            case CHRISTMAS -> {
                Set<Long> include = overrides.includeSet();
                Set<Long> exclude = overrides.excludeSet();
                yield new WeeklyTheme("holiday", "christmas", "This week: Christmas",
                    WeeklyTheme.STANDING_CAPTION, pick(library, f -> isChristmas(f, include, exclude)));
            }
        };
    }

    /** Not excluded, and either included or tagged with TMDB's {@code christmas} keyword. */
    private static boolean isChristmas(Film film, Set<Long> include, Set<Long> exclude) {
        Long tmdbId = film.getTmdbId();
        if (tmdbId != null && exclude.contains(tmdbId)) {
            return false;
        }
        return tmdbId != null && include.contains(tmdbId) || film.getKeywords().contains("christmas");
    }

    private static List<Film> pick(List<Film> library, Predicate<Film> matches) {
        return library.stream()
            .filter(matches)
            .sorted(Comparator.comparing(Film::getYear, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(Film::getSortTitle))
            .limit(MAX_FILMS)
            .toList();
    }

    /** Candidates in a stable order: decades ascending, then genres A-Z, then directors A-Z. */
    private static List<Candidate> candidates(List<Film> library) {
        List<Candidate> out = new ArrayList<>();
        Map<Integer, Integer> decades = new TreeMap<>();
        Map<String, Integer> genres = new TreeMap<>();
        Map<String, Integer> directors = new TreeMap<>();
        for (Film film : library) {
            if (film.getYear() != null) {
                decades.merge(film.getYear() / 10 * 10, 1, Integer::sum);
            }
            film.getGenres().forEach(g -> genres.merge(g, 1, Integer::sum));
            film.getCredits().stream()
                .filter(c -> c.getRole() == CreditRole.DIRECTOR)
                .map(c -> c.getPerson().getName())
                .distinct()
                .forEach(d -> directors.merge(d, 1, Integer::sum));
        }
        decades.forEach((decade, n) -> {
            if (n >= MIN_FILMS) {
                out.add(new Candidate("decade", String.valueOf(decade), "This week: the " + decade + "s",
                    f -> f.getYear() != null && f.getYear() / 10 * 10 == decade));
            }
        });
        genres.forEach((genre, n) -> {
            if (n >= MIN_FILMS) {
                out.add(new Candidate("genre", genre, "This week: " + genre, f -> f.getGenres().contains(genre)));
            }
        });
        directors.forEach((name, n) -> {
            if (n >= MIN_FILMS) {
                out.add(new Candidate("director", name, "This week: films by " + name,
                    f -> f.getCredits().stream()
                        .anyMatch(c -> c.getRole() == CreditRole.DIRECTOR && c.getPerson().getName().equals(name))));
            }
        });
        return out;
    }

    /** Scrambles the week key so consecutive weeks do not walk the candidate list in order. */
    private static long mix(int key) {
        long x = key * 0x9E3779B97F4A7C15L;
        x ^= x >>> 32;
        return Math.abs(x % 1_000_003L);
    }

    private record Candidate(String kind, String value, String title, Predicate<Film> matches) {
    }
}
