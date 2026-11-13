package gregochr.cinefilm.credits;

import gregochr.cinefilm.film.EditionTimeline;
import java.util.ArrayList;
import java.util.List;

/**
 * The last sanity check, on the edition timeline: a credits marker must be at least 60% into the edition and at
 * least 60 seconds before its end, or the result is only unsure. Credits are only ever looked for in the last part,
 * so the file offset is turned into edition time first.
 */
public final class EditionGuard {

    /**
     * A credits result in edition time.
     *
     * @param verdict the verdict after the guard
     * @param markerMs the marker in edition milliseconds; null when there is no place (not found, or unknown)
     * @param reasons plain sentences, the analysis's first and then the guard's
     */
    public record EditionVerdict(CreditsVerdict verdict, Long markerMs, List<String> reasons) {

        /** Copies the reasons. */
        public EditionVerdict {
            reasons = List.copyOf(reasons);
        }
    }

    private EditionGuard() {
    }

    /**
     * Converts and guards a result.
     *
     * @param result what the analysis found in the last part's file
     * @param timeline the edition's timeline
     * @param lastIndex the 0-based index of the part that was analysed (the last)
     * @return the verdict with the marker on the edition timeline; a found result outside the limits becomes unsure
     */
    public static EditionVerdict apply(CreditsResult result, EditionTimeline timeline, int lastIndex) {
        if (result.offsetMs() == null) {
            return new EditionVerdict(result.verdict(), null, result.reasons());
        }
        long markerMs;
        try {
            markerMs = timeline.toGlobal(lastIndex, result.offsetMs());
        } catch (IllegalArgumentException e) {
            List<String> reasons = new ArrayList<>(result.reasons());
            reasons.add("The detected time lies outside the part it was found in");
            return new EditionVerdict(CreditsVerdict.UNSURE, null, reasons);
        }
        if (result.verdict() != CreditsVerdict.FOUND) {
            return new EditionVerdict(result.verdict(), markerMs, result.reasons());
        }
        long total = timeline.totalMs();
        List<String> reasons = new ArrayList<>(result.reasons());
        if (markerMs * 100 < total * CreditsTuning.MIN_EDITION_PERCENT) {
            reasons.add("The credits would start only " + markerMs * 100 / total
                + "% into the film, and they should start at least " + CreditsTuning.MIN_EDITION_PERCENT + "% in");
        }
        if (total - markerMs < CreditsTuning.MIN_BEFORE_END_MS) {
            reasons.add("The credits would start less than a minute before the end of the film");
        }
        CreditsVerdict verdict = reasons.size() == result.reasons().size()
            ? CreditsVerdict.FOUND : CreditsVerdict.UNSURE;
        return new EditionVerdict(verdict, markerMs, reasons);
    }
}
