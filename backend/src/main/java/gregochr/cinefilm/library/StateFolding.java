package gregochr.cinefilm.library;

import gregochr.cinefilm.film.EditionTimeline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds the watch state of editions that become one (a join), and unfolds the state of one edition that becomes
 * several (a split). Pure: positions are edition times, converted with {@link EditionTimeline}.
 *
 * <p>Folding: an absorbed part's position becomes {@code partStart + position}. The result is built on the state that
 * was played last. It is watched only when the edition holding the last part was watched; a watched edition that does
 * not hold the last part becomes a resume position at the start of the next part. Unfolding: the global position is
 * located in the part that holds it; watched goes to the edition holding the last part.
 */
public final class StateFolding {

    private StateFolding() {
    }

    /**
     * A playback state, free of persistence.
     *
     * @param positionMs the resume position (0 when watched or not started)
     * @param watched whether it counts as watched
     * @param watchedAt when it became watched
     * @param lastPlayedAt when it was last played
     * @param hidden whether it is hidden from Continue watching
     * @param hiddenAt when it was hidden
     */
    public record State(long positionMs, boolean watched, Instant watchedAt, Instant lastPlayedAt, boolean hidden,
                        Instant hiddenAt) {
    }

    /**
     * One edition going into a join.
     *
     * @param startMs where its first part starts on the joined timeline
     * @param endMs where its last part ends on the joined timeline (the start of the next part)
     * @param holdsLastPart whether it holds the joined edition's last part
     * @param state its state, or null when it never had one
     */
    public record Source(long startMs, long endMs, boolean holdsLastPart, State state) {

        private boolean hasProgress() {
            return state != null && (state.watched() || state.positionMs() > 0);
        }

        private long progressMs() {
            return state.watched() ? endMs : startMs + state.positionMs();
        }
    }

    /**
     * Folds the states of the editions going into a join.
     *
     * @param sources the editions with their place on the joined timeline
     * @param totalMs the joined edition's runtime
     * @return the state of the joined edition, or null when none of the sources ever had one
     */
    public static State fold(List<Source> sources, long totalMs) {
        Source base = null;
        Instant latest = null;
        for (Source source : sources) {
            if (source.state() == null) {
                continue;
            }
            Instant played = source.state().lastPlayedAt();
            if (played != null && (latest == null || played.isAfter(latest))) {
                latest = played;
            }
            if (base == null || played != null && (base.state().lastPlayedAt() == null
                || played.isAfter(base.state().lastPlayedAt()))) {
                base = source;
            }
        }
        if (base == null) {
            return null;
        }
        State lastHolder = sources.stream().filter(Source::holdsLastPart).findFirst().map(Source::state).orElse(null);
        if (lastHolder != null && lastHolder.watched()) {
            return new State(0, true, lastHolder.watchedAt(), latest, base.state().hidden(), base.state().hiddenAt());
        }
        long position = 0;
        if (base.hasProgress()) {
            position = base.progressMs();
        } else {
            for (Source source : sources) {
                if (source.hasProgress()) {
                    position = Math.max(position, source.progressMs());
                }
            }
        }
        return new State(Math.min(position, totalMs), false, null, latest, base.state().hidden(),
            base.state().hiddenAt());
    }

    /**
     * Unfolds the state of one multi-part edition across the editions its parts are split into.
     *
     * @param state the state of the edition being split, or null
     * @param partDurationsMs the durations of its parts, in play order
     * @param groups for each new edition, the 0-based indexes of the parts it keeps, in play order
     * @return one state per group (null where the group gets none): the group holding the part the position is in gets
     *     the resume position, or, when watched, the group holding the last part is watched
     */
    public static List<State> unfold(State state, List<Long> partDurationsMs, List<List<Integer>> groups) {
        List<State> result = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            result.add(null);
        }
        if (state == null) {
            return result;
        }
        if (state.watched()) {
            int last = partDurationsMs.size() - 1;
            result.set(groupOf(groups, last), new State(0, true, state.watchedAt(), state.lastPlayedAt(),
                state.hidden(), state.hiddenAt()));
            return result;
        }
        Located at = relocate(state.positionMs(), partDurationsMs, groups);
        result.set(at.group(), new State(at.positionMs(), false, null, state.lastPlayedAt(), state.hidden(),
            state.hiddenAt()));
        return result;
    }

    /**
     * Where an edition time lands once the edition is split.
     *
     * @param group the index of the new edition that holds it
     * @param positionMs the time on that edition's own timeline
     */
    public record Located(int group, long positionMs) {
    }

    /**
     * Finds where an edition time lands after a split: the new edition holding the part it is in, and the time on that
     * edition's own timeline.
     *
     * @param globalMs the time on the whole edition's timeline
     * @param partDurationsMs the durations of its parts, in play order
     * @param groups for each new edition, the 0-based indexes of the parts it keeps, in play order
     * @return the new edition and the time on it
     */
    public static Located relocate(long globalMs, List<Long> partDurationsMs, List<List<Integer>> groups) {
        EditionTimeline.Position at = EditionTimeline.of(partDurationsMs).locate(globalMs);
        int group = groupOf(groups, at.partIndex());
        List<Integer> parts = groups.get(group);
        long before = 0;
        for (int i = 0; parts.get(i) != at.partIndex(); i++) {
            before += partDurationsMs.get(parts.get(i));
        }
        return new Located(group, before + at.offsetMs());
    }

    private static int groupOf(List<List<Integer>> groups, int part) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).contains(part)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Part " + part + " is in no group");
    }
}
