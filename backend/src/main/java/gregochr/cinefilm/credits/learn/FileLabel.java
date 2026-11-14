package gregochr.cinefilm.credits.learn;

import java.util.List;

/**
 * What the labels say about one file, before the split is added.
 *
 * @param excluded why the whole file is left out, or null when it is labelled
 * @param targetStartMs the start of the earliest merged credits interval, shifted; null when excluded
 * @param shiftMs the shift onto this copy; null when excluded
 * @param leadingIsland whether the first credits interval is a short island; null when excluded
 * @param credits the credits stretches (empty when excluded)
 * @param film the film stretches (empty when excluded)
 * @param leftOut the stretches left out, with reasons (empty when excluded)
 */
public record FileLabel(ExcludeReason excluded, Long targetStartMs, Long shiftMs, Boolean leadingIsland,
                        List<Span> credits, List<Span> film, List<LeftOut> leftOut) {

    /** The label of a file left out entirely. */
    static FileLabel excluded(ExcludeReason reason) {
        return new FileLabel(reason, null, null, null, List.of(), List.of(), List.of());
    }
}
