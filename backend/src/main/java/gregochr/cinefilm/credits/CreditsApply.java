package gregochr.cinefilm.credits;

import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.EditionTimeline;
import java.util.List;

/**
 * Decides what an edition's credits marker and its source should be, from what is known about it now. Pure: no
 * Spring, no repositories. The order is fixed: an owner's marker is never touched; otherwise Plex's marker for the
 * last part applies when it still describes the same file and lies inside the {@link EditionGuard} limits;
 * otherwise (step 3, an automatic marker, is reserved for a later phase and never applies yet) the marker and its
 * source are both cleared.
 */
public final class CreditsApply {

    /**
     * One original part of an edition.
     *
     * @param mediaFileId the part's media file
     * @param timelineDurationMs how long the part is on the edition timeline; null when unknown
     */
    public record Part(long mediaFileId, Long timelineDurationMs) {
    }

    /**
     * What Plex said about the last part's file.
     *
     * @param fingerprint the file's fingerprint when imported
     * @param startMs where the credits start inside the file
     */
    public record PlexRow(String fingerprint, long startMs) {
    }

    /**
     * The marker an edition should have.
     *
     * @param markerMs the marker in edition milliseconds; null when there is none
     * @param source where it came from; null exactly when {@code markerMs} is null
     */
    public record Applied(Long markerMs, CreditsMarkerSource source) {
    }

    /** Why Plex's row does or does not apply to an edition, in the order the checks run. */
    public enum PlexState {
        /** Plex has no row for the last part's file. */
        NONE,
        /** The last part has no fingerprint, so the row cannot be matched to it. */
        NO_FINGERPRINT,
        /** The row's fingerprint differs from the file's: it describes an older copy. */
        STALE,
        /** The edition's parts have no timeline (no original part, or a part with no duration). */
        NO_TIMELINE,
        /** The row's offset lies outside the last part. */
        OUTSIDE,
        /** The time is outside the {@link EditionGuard} limits. */
        REFUSED,
        /** The pass applies the row. */
        USABLE
    }

    /**
     * Plex's row as it stands for an edition.
     *
     * @param state which check decided
     * @param markerMs Plex's time in edition milliseconds for {@code REFUSED} and {@code USABLE}; null otherwise
     */
    public record PlexStatus(PlexState state, Long markerMs) {
    }

    private CreditsApply() {
    }

    /**
     * Works out the marker and source for one edition.
     *
     * @param parts the edition's original parts in play order (may be empty)
     * @param lastFingerprint the last part's file's current fingerprint; null when unknown
     * @param markerMs the edition's current marker; null when none
     * @param source the current marker's source; null when none (or stored before sources existed)
     * @param plexRow Plex's row for the last part's file; null when there is none
     * @return the marker and source the edition should have
     */
    public static Applied apply(List<Part> parts, String lastFingerprint, Long markerMs,
                                CreditsMarkerSource source, PlexRow plexRow) {
        if (source == CreditsMarkerSource.OWNER) {
            return new Applied(markerMs, source);
        }
        PlexStatus status = plexStatus(parts, lastFingerprint, plexRow);
        if (status.state() == PlexState.USABLE) {
            return new Applied(status.markerMs(), CreditsMarkerSource.PLEX);
        }
        return new Applied(null, null);
    }

    /**
     * What Plex's row means for an edition, whatever the edition's source is. The checks run in the order of
     * {@link PlexState}, and {@link #apply} applies the row exactly when the state is {@code USABLE}, so a list and
     * the pass cannot disagree.
     *
     * @param parts the edition's original parts in play order (may be empty)
     * @param lastFingerprint the last part's file's current fingerprint; null when unknown
     * @param plexRow Plex's row for the last part's file; null when there is none
     * @return the state, and Plex's time in edition milliseconds for {@code REFUSED} and {@code USABLE} only
     */
    public static PlexStatus plexStatus(List<Part> parts, String lastFingerprint, PlexRow plexRow) {
        if (plexRow == null) {
            return new PlexStatus(PlexState.NONE, null);
        }
        if (lastFingerprint == null) {
            return new PlexStatus(PlexState.NO_FINGERPRINT, null);
        }
        if (!lastFingerprint.equals(plexRow.fingerprint())) {
            return new PlexStatus(PlexState.STALE, null);
        }
        EditionTimeline timeline;
        try {
            timeline = EditionTimeline.of(parts.stream().map(Part::timelineDurationMs).toList());
        } catch (IllegalArgumentException e) {
            return new PlexStatus(PlexState.NO_TIMELINE, null);
        }
        long global;
        try {
            global = timeline.toGlobal(parts.size() - 1, plexRow.startMs());
        } catch (IllegalArgumentException e) {
            return new PlexStatus(PlexState.OUTSIDE, null);
        }
        return new PlexStatus(EditionGuard.plausible(global, timeline.totalMs())
            ? PlexState.USABLE : PlexState.REFUSED, global);
    }
}
