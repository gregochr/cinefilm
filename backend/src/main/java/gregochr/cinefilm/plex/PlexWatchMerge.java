package gregochr.cinefilm.plex;

import gregochr.cinefilm.playback.WatchedRule;
import java.time.Instant;

/**
 * Merges what Plex knows of a film's watching into one edition's state. Plex keeps one state per film and CineFilm one
 * per edition, so the caller passes the same Plex facts for every paired edition and says which edition carries the
 * resume point. Never downgrades, and never overwrites a CineFilm state that is newer than Plex's.
 */
public final class PlexWatchMerge {

    private PlexWatchMerge() {
    }

    /**
     * What Plex says about the film.
     *
     * @param viewCount how many times it was watched
     * @param viewOffsetMs the resume position in milliseconds (0 when none)
     * @param lastViewedAt when it was last watched, or null
     * @param updatedAt when Plex last changed the film, or null
     */
    public record Plex(int viewCount, long viewOffsetMs, Instant lastViewedAt, Instant updatedAt) {
    }

    /**
     * An edition's state in CineFilm.
     *
     * @param positionMs the resume position
     * @param watched whether it is watched
     * @param watchedAt when it became watched, or null
     * @param lastPlayedAt when it was last played, or null
     */
    public record Existing(long positionMs, boolean watched, Instant watchedAt, Instant lastPlayedAt) {
    }

    /**
     * The edition's length, for the watched rule.
     *
     * @param runtimeMs runtime in milliseconds
     * @param creditsMarkerMs where the credits start, or null
     */
    public record Cut(long runtimeMs, Long creditsMarkerMs) {
    }

    /** What the merge decided. */
    public enum Change {
        /** Plex has no watching for this edition: nothing to write. */
        NONE,
        /** The merged state is what the edition already has. */
        UNCHANGED,
        /** The edition's own state is newer than Plex's, so it stays as it is. */
        KEPT_NEWER,
        /** The merged state differs from the edition's: write it. */
        UPDATED
    }

    /**
     * The state to write, and what it means for the report.
     *
     * @param change what was decided
     * @param positionMs the resume position to store
     * @param watched whether the edition is watched
     * @param watchedAt when it became watched, or null
     * @param lastPlayedAt when it was last played, or null
     * @param newlyWatched true when this merge made the edition watched
     * @param newResumePoint true when this merge set a resume position the edition did not have
     */
    public record Result(Change change, long positionMs, boolean watched, Instant watchedAt, Instant lastPlayedAt,
                         boolean newlyWatched, boolean newResumePoint) {
    }

    /**
     * Merges Plex's watching into an edition.
     *
     * @param existing the edition's current state, or null when it has none
     * @param plex what Plex says
     * @param carriesOffset true for the edition whose file Plex lists first: only it gets the resume point
     * @param cut the edition's runtime and credits marker
     * @param importTime when the import started (the last resort for a play time)
     * @return the state to write
     */
    public static Result merge(Existing existing, Plex plex, boolean carriesOffset, Cut cut, Instant importTime) {
        long offset = carriesOffset ? plex.viewOffsetMs() : 0;
        if (plex.viewCount() <= 0 && offset <= 0) {
            return keep(existing, Change.NONE);
        }
        Instant played = playedAt(existing, plex, importTime);
        if (existing != null && existing.lastPlayedAt() != null && existing.lastPlayedAt().isAfter(played)) {
            return keep(existing, Change.KEPT_NEWER);
        }
        boolean nearEnd = offset > 0 && WatchedRule.isWatched(offset, cut.runtimeMs(), cut.creditsMarkerMs());
        boolean watched = plex.viewCount() > 0 || nearEnd || (existing != null && existing.watched());
        long position = offset > 0 && !nearEnd ? offset : 0;
        Instant watchedAt = existing != null && existing.watched() && existing.watchedAt() != null
            ? existing.watchedAt() : played;
        if (!watched) {
            watchedAt = null;
        }
        boolean newlyWatched = watched && (existing == null || !existing.watched());
        boolean newResumePoint = position > 0 && (existing == null || existing.positionMs() != position);
        Existing merged = new Existing(position, watched, watchedAt, played);
        Change change = merged.equals(existing) ? Change.UNCHANGED : Change.UPDATED;
        return new Result(change, position, watched, watchedAt, played, newlyWatched, newResumePoint);
    }

    /** Plex's last view, else when Plex last changed the film, else what the edition already says, else now. */
    private static Instant playedAt(Existing existing, Plex plex, Instant importTime) {
        if (plex.lastViewedAt() != null) {
            return plex.lastViewedAt();
        }
        if (plex.updatedAt() != null) {
            return plex.updatedAt();
        }
        return existing != null && existing.lastPlayedAt() != null ? existing.lastPlayedAt() : importTime;
    }

    private static Result keep(Existing existing, Change change) {
        if (existing == null) {
            return new Result(change, 0, false, null, null, false, false);
        }
        return new Result(change, existing.positionMs(), existing.watched(), existing.watchedAt(),
            existing.lastPlayedAt(), false, false);
    }
}
