package gregochr.cinefilm.library;

import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.library.StateFolding.Source;
import gregochr.cinefilm.library.StateFolding.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries playback state and credits markers from the editions as they were to the editions as the owner arranged
 * them (Edit editions). Every position is placed by <em>file</em>, so reordering or interleaving files cannot shift it:
 * the position (and the marker) is located in the old edition's own timeline, which gives the file it was in and the
 * offset inside that file; that offset is moved onto the file's start on the new edition's timeline
 * ({@link EditionTimeline}); and the pieces landing in one edition are folded with {@link StateFolding#fold}, so
 * watched and furthest-progress merge by the same rules as a join. A watched edition is watched for the files it had
 * (it goes where its last file went). Pure: nothing is read or written.
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
     * What one old edition hands to one new edition: either a state (whose position is the offset inside its one file,
     * or 0 for a watched state spanning all of {@code fileIds}) or a credits marker placed in one file.
     *
     * @param state the state, or null for a marker piece
     * @param fileIds the files the state covers
     * @param markerFileId the file the credits marker is in, or null
     * @param markerOffsetMs the marker's offset inside that file
     */
    record Piece(State state, List<Long> fileIds, Long markerFileId, Long markerOffsetMs) {
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
     * Places each old edition's state and marker in the new edition its file went to. What was in a file that leaves
     * the film leaves with it. An edition with a file of no known duration is not carried.
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
            EditionTimeline timeline = EditionTimeline.of(old.durations());
            State state = old.state();
            if (state != null && state.watched()) {
                Long target = targetOfFile.get(old.fileIds().get(old.fileIds().size() - 1));
                List<Long> there = old.fileIds().stream()
                    .filter(f -> target != null && target.equals(targetOfFile.get(f))).toList();
                add(pieces, target, new Piece(state, there, null, null));
            } else if (state != null) {
                EditionTimeline.Position at = timeline.locate(state.positionMs());
                Long file = old.fileIds().get(at.partIndex());
                State inFile = new State(at.offsetMs(), false, null, state.lastPlayedAt(), state.hidden(),
                    state.hiddenAt());
                add(pieces, targetOfFile.get(file), new Piece(inFile, List.of(file), null, null));
            }
            if (old.markerMs() != null) {
                EditionTimeline.Position at = timeline.locate(old.markerMs());
                Long file = old.fileIds().get(at.partIndex());
                add(pieces, targetOfFile.get(file), new Piece(null, List.of(file), file, at.offsetMs()));
            }
        }
        return pieces;
    }

    private static void add(Map<Long, List<Piece>> pieces, Long target, Piece piece) {
        if (target != null) {
            pieces.computeIfAbsent(target, k -> new ArrayList<>()).add(piece);
        }
    }

    /**
     * Folds the pieces a new edition receives onto its own timeline. The credits marker is kept only when it is in the
     * edition's last file.
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
        int lastIndex = orderedFileIds.size() - 1;
        List<Source> sources = new ArrayList<>();
        Long marker = null;
        for (Piece piece : pieces) {
            if (piece.markerFileId() != null) {
                int at = orderedFileIds.indexOf(piece.markerFileId());
                if (at == lastIndex) {
                    marker = timeline.partStartMs(at) + piece.markerOffsetMs();
                }
                continue;
            }
            List<Integer> at = piece.fileIds().stream().map(orderedFileIds::indexOf).sorted().toList();
            int first = at.get(0);
            int last = at.get(at.size() - 1);
            State state = piece.state();
            sources.add(new Source(timeline.partStartMs(first), timeline.partStartMs(last) + durations.get(last),
                last == lastIndex, state));
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
