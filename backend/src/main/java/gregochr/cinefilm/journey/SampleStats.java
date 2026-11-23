package gregochr.cinefilm.journey;

import java.time.Instant;

/** The count of a journey's samples and the first and the last moment among them. */
public record SampleStats(Long journeyId, long count, Instant earliest, Instant latest) {
}
