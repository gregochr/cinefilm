package gregochr.cinefilm.tmdb;

import gregochr.cinefilm.library.TitleGuess;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Chooses the TMDB film for a file. Candidates are scored on title (exact, or a small spelling distance), year
 * (exact or within one), runtime (within eight minutes of the file's duration, the Wicker Man rule) and, only as a
 * tie-break, popularity. A candidate is accepted automatically at {@value #ACCEPT_SCORE} points or more with a lead of
 * {@value #MARGIN} over the runner-up; otherwise the top five are kept for the owner.
 */
@Component
public class TmdbMatcher {

    /** Minimum score for an automatic match. Exact title alone (50) is not enough; with the year or runtime it is. */
    static final int ACCEPT_SCORE = 60;
    /** How far ahead of the second candidate the best must be. */
    static final int MARGIN = 10;
    static final int MAX_CANDIDATES = 5;
    static final long RUNTIME_TOLERANCE_MS = 8 * 60_000L;
    private static final double EXACT_TITLE = 50;
    private static final double SPELLING_PENALTY = 12;
    private static final int MAX_SPELLING_DISTANCE = 4;
    private static final double MIN_SIMILARITY = 0.8;
    private static final double YEAR_EXACT = 25;
    private static final double YEAR_NEAR = 15;
    private static final double RUNTIME_MATCH = 25;
    private static final double POPULARITY_HALF = 50;
    private static final int MIN_TRUNCATABLE = 6;
    private static final double TRUNCATE_TO = 0.7;

    private final TmdbApi tmdb;

    /** Creates the matcher over the TMDB API. */
    public TmdbMatcher(TmdbApi tmdb) {
        this.tmdb = tmdb;
    }

    /** One search to run. */
    record Query(String title, Integer year) {
    }

    /**
     * Looks the file up.
     *
     * @param guess the title and year guessed from the file
     * @param durationMs the file's duration (0 when unknown)
     * @return the outcome; a TMDB outage is {@link Status#UNAVAILABLE}, never an exception
     */
    public MatchOutcome match(TitleGuess guess, long durationMs) {
        try {
            return decide(guess, durationMs);
        } catch (TmdbUnavailableException e) {
            return new MatchOutcome(Status.UNAVAILABLE, null, List.of());
        }
    }

    /**
     * Looks a film up by the id its file name carries: the {@code {tmdb-N}} tag fetches that movie directly, else the
     * {@code {imdb-tt...}} tag is resolved through TMDB's find endpoint. Nothing is searched or scored: the tag is the
     * owner's word.
     *
     * @param tmdbId the TMDB id tag, or null
     * @param imdbId the IMDb id tag, or null
     * @return the matched outcome, an {@link Status#UNAVAILABLE} one on an outage, or empty when no tag names a film
     *     TMDB knows (the caller then searches by title)
     */
    public Optional<MatchOutcome> matchTagged(Long tmdbId, String imdbId) {
        try {
            Optional<MovieDetails> movie = tmdbId == null ? Optional.empty() : tmdb.movie(tmdbId);
            if (movie.isEmpty() && imdbId != null) {
                movie = tmdb.findMovieByImdbId(imdbId).flatMap(tmdb::movie);
            }
            return movie.map(m -> new MatchOutcome(Status.MATCHED, m, List.of()));
        } catch (TmdbUnavailableException e) {
            return Optional.of(new MatchOutcome(Status.UNAVAILABLE, null, List.of()));
        }
    }

    private MatchOutcome decide(TitleGuess guess, long durationMs) {
        Map<Long, SearchHit> hits = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        for (Query query : queries(guess)) {
            used.add(normalise(query.title()));
            List<SearchHit> found = tmdb.search(query.title(), query.year());
            found.forEach(h -> hits.putIfAbsent(h.id(), h));
            if (found.stream().anyMatch(h -> exact(h, normalise(query.title())))) {
                break;
            }
        }
        List<Scored> scored = hits.values().stream()
            .filter(h -> titleScore(h, used) > 0)
            .map(h -> new Scored(h, titleScore(h, used) + yearScore(h.year(), guess.year())
                + popularity(h.popularity())))
            .sorted(Comparator.comparingDouble(Scored::score).reversed())
            .limit(MAX_CANDIDATES).toList();
        List<Ranked> ranked = new ArrayList<>();
        for (Scored s : scored) {
            Optional<MovieDetails> details = tmdb.movie(s.hit().id());
            Integer year = details.map(MovieDetails::year).orElse(s.hit().year());
            Integer runtime = details.map(MovieDetails::runtimeMinutes).orElse(null);
            double total = titleScore(s.hit(), used) + yearScore(year, guess.year())
                + runtimeScore(runtime, durationMs) + popularity(s.hit().popularity());
            ranked.add(new Ranked(details.orElse(null), s.hit(), year, runtime, total));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::total).reversed());
        List<Candidate> candidates = ranked.stream().map(TmdbMatcher::candidate).toList();
        if (!ranked.isEmpty() && accepted(ranked) && ranked.get(0).details() != null) {
            return new MatchOutcome(Status.MATCHED, ranked.get(0).details(), candidates);
        }
        return new MatchOutcome(Status.NEEDS_MATCH, null, candidates);
    }

    private static boolean accepted(List<Ranked> ranked) {
        double best = ranked.get(0).total();
        double second = ranked.size() > 1 ? ranked.get(1).total() : Double.NEGATIVE_INFINITY;
        return best >= ACCEPT_SCORE && best - second >= MARGIN;
    }

    private static Candidate candidate(Ranked r) {
        return new Candidate(r.hit().id(), r.details() != null ? r.details().title() : r.hit().title(), r.year(),
            r.runtime(), r.details() != null ? r.details().posterPath() : r.hit().posterPath(),
            (int) Math.round(r.total()));
    }

    private record Scored(SearchHit hit, double score) {
    }

    private record Ranked(MovieDetails details, SearchHit hit, Integer year, Integer runtime, double total) {
    }

    /**
     * The searches to try, in order, until one returns an exact title: the title with the year, without the year,
     * without its last word ("Cartoon"), and with the last word cut short so TMDB's prefix matching can find a
     * misspelling ("Dalmations" becomes "Dalmati").
     */
    static List<Query> queries(TitleGuess guess) {
        Set<Query> queries = new LinkedHashSet<>();
        String title = guess.title();
        queries.add(new Query(title, guess.year()));
        if (guess.year() != null) {
            queries.add(new Query(title, null));
        }
        int space = title.lastIndexOf(' ');
        String dropped = space > 0 ? title.substring(0, space).trim() : "";
        if (!dropped.isEmpty()) {
            queries.add(new Query(dropped, null));
        }
        truncated(title).ifPresent(t -> queries.add(new Query(t, null)));
        truncated(dropped).ifPresent(t -> queries.add(new Query(t, null)));
        return List.copyOf(queries);
    }

    private static Optional<String> truncated(String title) {
        int space = title.lastIndexOf(' ');
        String last = title.substring(space + 1);
        if (last.length() < MIN_TRUNCATABLE) {
            return Optional.empty();
        }
        int keep = (int) Math.ceil(last.length() * TRUNCATE_TO);
        return Optional.of(title.substring(0, space + 1) + last.substring(0, keep));
    }

    private static boolean exact(SearchHit hit, String query) {
        return query.equals(normalise(hit.title())) || query.equals(normalise(hit.originalTitle()));
    }

    private static double titleScore(SearchHit hit, List<String> queries) {
        double best = 0;
        String title = normalise(hit.title());
        String original = normalise(hit.originalTitle());
        for (String query : queries) {
            best = Math.max(best, Math.max(similarity(query, title), similarity(query, original)));
        }
        return best;
    }

    private static double similarity(String query, String title) {
        if (title.isEmpty()) {
            return 0;
        }
        int distance = distance(query, title);
        double similarity = 1.0 - (double) distance / Math.max(query.length(), title.length());
        if (distance > MAX_SPELLING_DISTANCE || similarity < MIN_SIMILARITY) {
            return 0;
        }
        return EXACT_TITLE - SPELLING_PENALTY * distance;
    }

    private static double yearScore(Integer candidate, Integer wanted) {
        if (candidate == null || wanted == null) {
            return 0;
        }
        int difference = Math.abs(candidate - wanted);
        if (difference == 0) {
            return YEAR_EXACT;
        }
        return difference == 1 ? YEAR_NEAR : 0;
    }

    private static double runtimeScore(Integer runtimeMinutes, long durationMs) {
        if (runtimeMinutes == null || durationMs <= 0) {
            return 0;
        }
        return Math.abs(runtimeMinutes * 60_000L - durationMs) <= RUNTIME_TOLERANCE_MS ? RUNTIME_MATCH : 0;
    }

    /** Under one point: it can only separate candidates that are otherwise level. */
    private static double popularity(double popularity) {
        return popularity / (popularity + POPULARITY_HALF);
    }

    /** Lower case, no accents, "&" as "and", punctuation as single spaces, no leading "the". */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String plain = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return plain.startsWith("the ") ? plain.substring(4) : plain;
    }

    /** Levenshtein distance: the fewest single-character edits that turn one text into the other. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
