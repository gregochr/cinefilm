package gregochr.cinefilm.library;

/**
 * Published when a library scan has finished (successfully or not).
 *
 * @param trigger what started it: {@code startup}, {@code nightly} or {@code admin}
 */
public record ScanFinished(String trigger) {
}
