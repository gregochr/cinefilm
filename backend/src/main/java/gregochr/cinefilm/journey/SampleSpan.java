package gregochr.cinefilm.journey;

import java.time.Instant;

/** The first and the last moment a journey was sampled; both null for a journey with no sample. */
public record SampleSpan(Instant earliest, Instant latest) {
}
