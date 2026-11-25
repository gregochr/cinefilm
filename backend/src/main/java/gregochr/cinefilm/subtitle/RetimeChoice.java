package gregochr.cinefilm.subtitle;

/**
 * What the retime job decided for a downloaded subtitle (docs/engineering/subtitle-frame-rate-plan.md); a row that is
 * not decided yet has none (null).
 */
public enum RetimeChoice {
    /** Retime it: its cues are put on the film's timeline. */
    AUTO,
    /** Leave it alone: the owner's hand-set timing is kept. */
    OFF,
    /** Fits the film as it is: it counts as on the film's timeline, like an owner's file. */
    OFF_FITS
}
