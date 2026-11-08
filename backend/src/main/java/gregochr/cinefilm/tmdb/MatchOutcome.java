package gregochr.cinefilm.tmdb;

import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.util.List;

/**
 * The result of looking a file up on TMDB.
 *
 * @param status what happened
 * @param movie the chosen film when {@link Status#MATCHED}, else null
 * @param candidates the best candidates, best first (the match itself is first when matched)
 */
public record MatchOutcome(Status status, MovieDetails movie, List<Candidate> candidates) {

    /** What the lookup concluded. */
    public enum Status {
        /** One candidate scored high enough and clearly beat the rest. */
        MATCHED,
        /** No confident answer; the candidates are for the owner to choose from. */
        NEEDS_MATCH,
        /** TMDB could not be reached; try again on the next scan. */
        UNAVAILABLE
    }

    /**
     * A possible match, as stored and shown in Admin.
     *
     * @param tmdbId TMDB id
     * @param title title
     * @param year release year, or null
     * @param runtimeMinutes runtime in minutes, or null when unknown
     * @param posterPath poster path, or null
     * @param score the match score (higher is better; 60 or more with a clear lead is accepted)
     */
    public record Candidate(long tmdbId, String title, Integer year, Integer runtimeMinutes, String posterPath,
                            int score) {
    }
}
