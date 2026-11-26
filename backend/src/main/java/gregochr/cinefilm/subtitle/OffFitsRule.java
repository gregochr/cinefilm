package gregochr.cinefilm.subtitle;

import java.util.List;

/**
 * Whether a download that would be retimed (k is not 1) already fits the film as it is
 * (docs/engineering/subtitle-frame-rate-plan.md, "What the owner set by hand is left alone"). Pure.
 *
 * <p>A file whose original cues fit the film's own English track {@code ALIGNED} is on this copy's timeline already, so
 * its stated rate is wrong and it is left as it is ({@link RetimeChoice#OFF_FITS}). Any other verdict means retime it.
 * On a film that may have an English track of its own, the decision waits until the track has been read; a film that
 * cannot have one is decided at once.
 */
final class OffFitsRule {

    /** What to do with the download. */
    enum Decision {
        /** The film's track has not been read yet: leave the download undecided, a later run decides. */
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
     * @param mayHaveEnglishTrack true when a stream of the film's files could be its full English track
     *     ({@link EmbeddedStreams#mayBeFullEnglish})
     * @param reference what the film's files read as ({@link TimingKeys#embeddedReference}), or null when a file has
     *     no current read yet
     * @param originalStarts the download's original cue starts in milliseconds
     * @return the decision
     */
    static Decision decide(boolean mayHaveEnglishTrack, TimingKeys.EmbeddedReference reference,
                           List<Long> originalStarts) {
        if (reference == null) {
            return mayHaveEnglishTrack ? Decision.WAIT : Decision.RETIME;
        }
        if (reference.noReference() != null) {
            return Decision.RETIME;
        }
        return TimingFit.fit(originalStarts, reference.starts()).verdict() == TimingFit.Verdict.ALIGNED
            ? Decision.FITS_AS_IS : Decision.RETIME;
    }
}
