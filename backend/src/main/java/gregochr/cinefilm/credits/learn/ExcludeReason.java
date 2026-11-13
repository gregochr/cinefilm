package gregochr.cinefilm.credits.learn;

/**
 * Why a whole file is left out of the labels. The declaration order is the closed-set order of the plan: when several
 * reasons apply to a file, the first one is recorded.
 */
public enum ExcludeReason {
    /** The file is not its edition's last original part, the one the product analyses. */
    NOT_LAST_PART,
    /** The file is not {@code PRESENT} in the library now. */
    NOT_PRESENT,
    /** The file's fingerprint now differs from the one the Plex import stored. */
    FINGERPRINT_CHANGED,
    /** No archive item has this file. */
    NO_ARCHIVE_ITEM,
    /** More than one archive item has this file. */
    AMBIGUOUS_ARCHIVE_ITEM,
    /** The archive item has no credits marker. */
    NO_CREDITS_MARKER,
    /** A credits marker has no end. */
    MISSING_MARKER_END,
    /** The recomputed shift is not the stored start minus the earliest marker. */
    SHIFT_MISMATCH,
    /** The recomputed shift is outside 0 to 4 seconds. */
    SHIFT_OUT_OF_RANGE,
    /** The earliest credits marker starts before the analysis window. */
    MARKER_BEFORE_WINDOW,
    /** The owner's exclusions file lists the file. */
    OWNER_EXCLUDED,
    /** The film's unit has earlier members on both sides of the split. */
    SPLIT_CONFLICT
}
