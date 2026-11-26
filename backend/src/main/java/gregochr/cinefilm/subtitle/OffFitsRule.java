package gregochr.cinefilm.subtitle;

import java.util.List;

/**
 * Whether a download that would be retimed (k is not 1) already fits the film as it is
 * (docs/engineering/subtitle-frame-rate-plan.md, "What the owner set by hand is left alone"). Pure.
 *
 * <p>A file whose original cues fit the film's own English track {@code ALIGNED} is on this copy's timeline already, so
 * its stated rate is wrong and it is left as it is ({@link RetimeChoice#OFF_FITS}). Any other verdict means retime it.
 * The decision waits only for a forced download that the timing check will fit and has not settled yet (it has saved
 * no fit for it): the check does the reading of the film's track, whatever its codec. Any other download is decided
 * from the rates at once.
 */
final class OffFitsRule {

    /** What to do with the download. */
    enum Decision {
        /** The timing check has not settled the download yet: leave it undecided, a later run decides. */
        WAIT,
        /** The original cues fit the film's track: leave them (OFF_FITS). */
        FITS_AS_IS,
        /** Retime it (AUTO). */
        RETIME
    }

    private OffFitsRule() {
    }

    /**
     * Decides.
     *
     * @param fittedByTimingCheck true when the timing check fits this download (a forced download it can fit)
     * @param settled true when the timing check has saved a fit for it
     * @param reference what the film's files read as ({@link TimingKeys#embeddedReference}), or null when a file has
     *     no current read
     * @param originalStarts the download's original cue starts in milliseconds
     * @return the decision
     */
    static Decision decide(boolean fittedByTimingCheck, boolean settled, TimingKeys.EmbeddedReference reference,
                           List<Long> originalStarts) {
        if (!fittedByTimingCheck) {
            return Decision.RETIME;
        }
        if (!settled) {
            return Decision.WAIT;
        }
        if (reference == null || reference.noReference() != null) {
            return Decision.RETIME;
        }
        return TimingFit.fit(originalStarts, reference.starts()).verdict() == TimingFit.Verdict.ALIGNED
            ? Decision.FITS_AS_IS : Decision.RETIME;
    }
}
