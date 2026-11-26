package gregochr.cinefilm.subtitle;

import java.util.List;

/**
 * What the timing check says about a download, for the retime job's "does it fit as it is" decision
 * ({@link SidecarTimingService#offFitsState}): the check itself answers whether it will fit the file and whether it
 * has, on the film file as it is now.
 *
 * @param kind the answer
 * @param starts when {@link Kind#READY}: the film's English track's cue starts (empty when there is none)
 * @param noReference when {@link Kind#READY}: why the film has no usable track, or null
 */
public record OffFitsState(Kind kind, List<Long> starts, String noReference) {

    /** The answer. */
    public enum Kind {
        /** The check has read the film file and fitted the download on what it is now: the read decides. */
        READY,
        /** The check will fit the download but has not yet, on the film file as it is now. */
        PENDING,
        /** The check will not fit the download: it is off, does not serve it, or the film file's read keeps failing. */
        NEVER
    }

    /** The check will fit it and has not yet. */
    public static final OffFitsState PENDING = new OffFitsState(Kind.PENDING, List.of(), null);
    /** The check will never fit it. */
    public static final OffFitsState NEVER = new OffFitsState(Kind.NEVER, List.of(), null);

    /** The check has fitted it. */
    public static OffFitsState ready(List<Long> starts, String noReference) {
        return new OffFitsState(Kind.READY, starts, noReference);
    }
}
