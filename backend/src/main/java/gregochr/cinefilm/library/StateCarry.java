package gregochr.cinefilm.library;

import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.library.StateFolding.Located;
import gregochr.cinefilm.library.StateFolding.Source;
import gregochr.cinefilm.library.StateFolding.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries playback state and credits markers from the editions as they were to the editions as the owner arranged
 * them (Edit editions), with the same rules {@link EditionJoin} uses: the files of an old edition that go to several
 * new editions are relocated with {@link StateFolding#unfold}, and the pieces that land in one new edition are folded
 * onto its timeline with {@link StateFolding#fold}, positions and markers shifted by the part start from
 * {@link EditionTimeline}. Pure: nothing is read or written.
 */
final class StateCarry {

    private StateCarry() {
    }

    /**
     * An edition as it was before the save.
     *
     * @param fileIds its original files in play order
     * @param durations those files' timeline durations
     * @param state its playback state, or null
     * @param markerMs its credits marker, or null
     */
    record Old(List<Long> fileIds, List<Long> durations, State state, Long markerMs) {
    }

    /**
     * What one old edition hands to one new edition.
     *
     * @param state the state relocated to the files that went there, or null
     * @param markerMs the credits marker on those files' own timeline, or null
     * @param fileIds those files in their old order
     */
    record Piece(State state, Long markerMs, List<Long> fileIds) {
    }

    /**
     * What a new edition ends up with.
     *
     * @param state its state, or null for none
     * @param markerMs its credits marker, or null
     */
    record Result(State state, Long markerMs) {
    }

    /**
     * Splits each old edition's state and marker across the new editions its files went to. Files that go nowhere (Not
     * this film) take their share with them. An edition with a file of no known duration is not carried.
     *
     * @param olds the old editions
     * @param targetOfFile the new edition of each file, null for a file that leaves the film
     * @return the pieces each new edition receives
     */
    static Map<Long, List<Piece>> split(List<Old> olds, Map<Long, Long> targetOfFile) {
        Map<Long, List<Piece>> pieces = new LinkedHashMap<>();
        for (Old old : olds) {
            if ((old.state() == null && old.markerMs() == null) || !usable(old.durations())) {
                continue;
            }
            Map<Long, List<Integer>> byTarget = new LinkedHashMap<>();
            for (int i = 0; i < old.fileIds().size(); i++) {
                byTarget.computeIfAbsent(targetOfFile.get(old.fileIds().get(i)), k -> new ArrayList<>()).add(i);
            }
            List<Long> targets = new ArrayList<>(byTarget.keySet());
            List<List<Integer>> groups = new ArrayList<>(byTarget.values());
            List<State> states = StateFolding.unfold(old.state(), old.durations(), groups);
            Located marker = old.markerMs() == null ? null
                : StateFolding.relocate(old.markerMs(), old.durations(), groups);
            for (int g = 0; g < groups.size(); g++) {
                Long marked = marker != null && marker.group() == g ? marker.positionMs() : null;
                if (targets.get(g) != null && (states.get(g) != null || marked != null)) {
                    pieces.computeIfAbsent(targets.get(g), k -> new ArrayList<>()).add(new Piece(states.get(g),
                        marked, groups.get(g).stream().map(old.fileIds()::get).toList()));
                }
            }
        }
        return pieces;
    }

    /**
     * Folds the pieces a new edition receives onto its own timeline.
     *
     * @param pieces what the old editions handed to it
     * @param orderedFileIds its files in play order
     * @param timelineMs each file's timeline duration by id
     * @return its state and marker, or null when a file has no known duration (the caller then keeps what it has)
     */
    static Result land(List<Piece> pieces, List<Long> orderedFileIds, Map<Long, Long> timelineMs) {
        List<Long> durations = orderedFileIds.stream().map(timelineMs::get).toList();
        if (!usable(durations)) {
            return null;
        }
        EditionTimeline timeline = EditionTimeline.of(durations);
        List<Source> sources = new ArrayList<>();
        Long marker = null;
        for (Piece piece : pieces) {
            List<Integer> at = piece.fileIds().stream().map(orderedFileIds::indexOf).sorted().toList();
            int first = at.get(0);
            int last = at.get(at.size() - 1);
            boolean holdsLast = last == orderedFileIds.size() - 1;
            sources.add(new Source(timeline.partStartMs(first), timeline.partStartMs(last) + durations.get(last),
                holdsLast, piece.state()));
            if (holdsLast && piece.markerMs() != null) {
                marker = piece.markerMs() + timeline.partStartMs(first);
            }
        }
        return new Result(StateFolding.fold(sources, timeline.totalMs()), marker);
    }

    private static boolean usable(List<Long> durations) {
        return durations.stream().allMatch(d -> d != null && d > 0);
    }

    /** The files of a new edition in play order: by part, then id. */
    static List<Long> inPlayOrder(Map<Long, Integer> partOf, List<Long> fileIds) {
        return fileIds.stream().sorted(Comparator.comparingInt((Long id) -> partOf.get(id))
            .thenComparing(Comparator.naturalOrder())).toList();
    }
}
