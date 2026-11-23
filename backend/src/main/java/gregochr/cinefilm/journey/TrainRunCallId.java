package gregochr.cinefilm.journey;

/** The key of a {@link TrainRunCall}: its run and its place in the run's order. */
public record TrainRunCallId(Long trainRunId, Integer seq) {
}
