package gregochr.cinefilm.credits;

/**
 * Published after {@link CreditsDetectionService#save} has released the library lock, when the stored row is a version
 * 2 {@code FOUND} or replaced one: the credits apply pass may have an {@code AUTO} marker to set or clear.
 */
public record CreditsDetected() {
}
