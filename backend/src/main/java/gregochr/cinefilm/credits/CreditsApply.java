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
        Long plex = plexMarker(parts, lastFingerprint, plexRow);
        if (plex != null) {
            return new Applied(plex, CreditsMarkerSource.PLEX);
        }
        return new Applied(null, null);
    }

    private static Long plexMarker(List<Part> parts, String lastFingerprint, PlexRow plexRow) {
        if (plexRow == null || lastFingerprint == null || parts.isEmpty()
            || !lastFingerprint.equals(plexRow.fingerprint())) {
            return null;
        }
        try {
            EditionTimeline timeline = EditionTimeline.of(parts.stream().map(Part::timelineDurationMs).toList());
            long global = timeline.toGlobal(parts.size() - 1, plexRow.startMs());
            return EditionGuard.plausible(global, timeline.totalMs()) ? global : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
