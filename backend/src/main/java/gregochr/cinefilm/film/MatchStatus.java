package gregochr.cinefilm.film;

/** How a film's metadata was obtained. */
public enum MatchStatus {
    /** Matched to a TMDB film. */
    MATCHED,
    /** TMDB gave no confident answer; the top candidates are stored for the owner to choose from. */
    NEEDS_MATCH,
    /** Not looked up yet, or TMDB was unavailable; retried on the next scan. */
    PENDING,
    /** The owner chose to leave it without a TMDB match; not listed in To do, not retried. */
    LEFT_UNMATCHED
}
