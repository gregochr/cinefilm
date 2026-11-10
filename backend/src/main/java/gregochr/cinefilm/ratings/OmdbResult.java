package gregochr.cinefilm.ratings;

/**
 * What one OMDb lookup came to.
 *
 * @param outcome how it went
 * @param ratings the ratings when the outcome is {@link Outcome#FOUND}, otherwise null
 */
public record OmdbResult(Outcome outcome, OmdbRatings ratings) {

    /** How a lookup went. */
    public enum Outcome {
        /** OMDb knows the film; its ratings (any of which may be null) are in the result. */
        FOUND,
        /** OMDb does not know the film, so there are no ratings to show. */
        NOT_FOUND,
        /** The daily request limit is spent (or the key was refused): stop asking until tomorrow. */
        LIMIT_REACHED
    }

    /** A film OMDb knows. */
    public static OmdbResult found(OmdbRatings ratings) {
        return new OmdbResult(Outcome.FOUND, ratings);
    }

    /** A film OMDb does not know. */
    public static OmdbResult notFound() {
        return new OmdbResult(Outcome.NOT_FOUND, null);
    }

    /** The request limit is reached. */
    public static OmdbResult limitReached() {
        return new OmdbResult(Outcome.LIMIT_REACHED, null);
    }
}
