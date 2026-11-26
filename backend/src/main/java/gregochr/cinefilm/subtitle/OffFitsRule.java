package gregochr.cinefilm.subtitle;

import java.util.List;

/**
 * Whether a download that would be retimed (k is not 1) already fits the film as it is
 * (docs/engineering/subtitle-frame-rate-plan.md, "What the owner set by hand is left alone"). Pure.
 *
 * <p>A file whose original cues fit the film's own English track {@code ALIGNED} is on this copy's timeline already, so
 * its stated rate is wrong and it is left as it is ({@link RetimeChoice#OFF_FITS}). Any other verdict means retime it.
 * The decision waits only while the timing check will fit the download and has not yet, on the film file as it is now
 * ({@link OffFitsState}): the check does the reading of the film's track, whatever its codec. A download the check will
 * never fit is decided from the rates at once.
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
     * @param state what the timing check says about the download ({@link SidecarTimingService#offFitsState})
     * @param originalStarts the download's original cue starts in milliseconds
     * @return the decision
     */
    static Decision decide(OffFitsState state, List<Long> originalStarts) {
        return switch (state.kind()) {
            case NEVER -> Decision.RETIME;
            case PENDING -> Decision.WAIT;
            case READY -> state.noReference() == null
                && TimingFit.fit(originalStarts, state.starts()).verdict() == TimingFit.Verdict.ALIGNED
                ? Decision.FITS_AS_IS : Decision.RETIME;
        };
    }
}
