package gregochr.cinefilm.credits;

/** The result of the coarse step: either a place to look at closely, or the end of the matter. */
public sealed interface CoarseOutcome {

    /**
     * A qualifying run was found.
     *
     * @param startMs the offset of the run's first coarse sample (accurate to the 5 second sampling)
     */
    record Candidate(long startMs) implements CoarseOutcome {
    }

    /**
     * No qualifying run.
     *
     * @param result the {@link CreditsVerdict#NOT_FOUND} result with its reason
     */
    record Rejected(CreditsResult result) implements CoarseOutcome {
    }
}
