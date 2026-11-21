package gregochr.cinefilm.christmas;

import com.fasterxml.jackson.annotation.JsonProperty;
import gregochr.cinefilm.film.ChristmasChoice;
import java.time.LocalDate;
import java.util.List;

/** The Christmas films Admin API's request and response shapes. */
public final class ChristmasDtos {

    private ChristmasDtos() {
    }

    /** Why a film counts for Christmas week. */
    public enum Reason {
        /** TMDB tags it with the {@code christmas} keyword. */
        KEYWORD,
        /** The owner added it. */
        ADDED
    }

    /** Where a film stands for Christmas week. */
    public enum State {
        /** Counts through TMDB's keyword, not overridden. */
        IN_KEYWORD,
        /** Counts because the owner added it. */
        IN_ADDED,
        /** The owner left it out. */
        LEFT_OUT,
        /** Does not count. */
        NOT_IN,
        /** Has a TMDB id, does not count, and TMDB's keywords have not been fetched yet. */
        KEYWORDS_PENDING,
        /** Has no TMDB id, so it cannot be added. */
        NO_TMDB_ID
    }

    /** The Christmas period: a Monday to 25 December, both ends included. */
    public record WeekDto(LocalDate start, LocalDate end) {
    }

    /** The row Home shows holds at most {@code max} films, and a theme needs at least {@code min}. */
    public record Limits(int max, int min) {
    }

    /**
     * Keyword backfill progress: films with a TMDB id, those already asked about, whether a run is going, whether a
     * TMDB token is configured, and whether the backfill has stopped short (not running, films left, and either TMDB
     * is off or the last run could not finish).
     */
    public record Keywords(long fetched, long total, boolean running, boolean enabled, boolean stalled) {
    }

    /**
     * A film that counts for Christmas week (or was added). {@code posterUrl} is the stored poster path;
     * {@code override} is the owner's stored choice for its TMDB id (INCLUDE even when TMDB tags it too, so an Undo
     * can restore it; null when there is none).
     */
    public record Item(Long filmId, Long tmdbId, String title, Integer year, String posterUrl, Reason reason,
                       ChristmasChoice override) {
    }

    /** A film the owner left out, and whether TMDB tags it anyway; {@code override} is its stored choice (EXCLUDE). */
    public record LeftOutItem(Long filmId, Long tmdbId, String title, Integer year, String posterUrl,
                              boolean taggedByTmdb, ChristmasChoice override) {
    }

    /** The whole Christmas films screen. {@code fallsBack} is true when Home would not use the Christmas theme. */
    public record ChristmasView(WeekDto week, Limits limits, Keywords keywords, List<Item> inWeek, List<Item> added,
                                List<LeftOutItem> leftOut, boolean fallsBack) {
    }

    /**
     * The body of {@code PUT /{tmdbId}}: the {@code choice} field must be present; an explicit {@code null} forgets
     * the owner's choice, while a missing or misspelled field is a 400 rather than a silent forget.
     */
    public record ChoiceRequest(@JsonProperty(value = "choice", required = true) ChristmasChoice choice) {
    }

    /** One film's Christmas state. */
    public record FilmState(Long filmId, Long tmdbId, State state) {
    }
}
