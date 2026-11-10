package gregochr.cinefilm.library;

import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.library.StateFolding.Source;
import gregochr.cinefilm.library.StateFolding.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * @param rewatch true for the resume position of an edition that is watched and being watched again: it is
     *     carried beside the watched flag, never folded into it
     */
    record Piece(State state, List<Long> fileIds, Long markerFileId, Long markerOffsetMs, boolean rewatch) {

        /** A piece that is not a rewatch position. */
        Piece(State state, List<Long> fileIds, Long markerFileId, Long markerOffsetMs) {
            this(state, fileIds, markerFileId, markerOffsetMs, false);
        }
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
                if (state.positionMs() > 0) {
                    addPosition(pieces, old, timeline, state, targetOfFile);
                }
            } else if (state != null) {
                addPosition(pieces, old, timeline, state, targetOfFile);
            }
            if (old.markerMs() != null) {
                EditionTimeline.Position at = timeline.locate(old.markerMs());
                Long file = old.fileIds().get(at.partIndex());
                add(pieces, targetOfFile.get(file), new Piece(null, List.of(file), file, at.offsetMs()));
            }
        }
        return pieces;
    }

    /**
     * The new editions that receive a file from an old edition whose state or marker could not be carried because a
     * file has no known duration. For those the caller keeps what the edition has (its own state clamped to the new
     * runtime) instead of treating "nothing was carried" as "there is no state".
     *
     * @param olds the old editions
     * @param targetOfFile the new edition of each file, null for a file that leaves the film
     * @return the ids of those new editions
     */
    static Set<Long> uncarried(List<Old> olds, Map<Long, Long> targetOfFile) {
        Set<Long> targets = new HashSet<>();
        for (Old old : olds) {
            if ((old.state() != null || old.markerMs() != null) && !usable(old.durations())) {
                old.fileIds().forEach(file -> {
                    Long target = targetOfFile.get(file);
                    if (target != null) {
                        targets.add(target);
                    }
                });
            }
        }
        return targets;
    }

    /** Places a resume position by the file it was in: at the end of the earlier file when it is exactly on a join. */
    private static void addPosition(Map<Long, List<Piece>> pieces, Old old, EditionTimeline timeline, State state,
                                    Map<Long, Long> targetOfFile) {
        EditionTimeline.Position at = timeline.locate(state.positionMs());
        int index = at.partIndex();
        long offset = at.offsetMs();
        if (offset == 0 && state.positionMs() > 0) {
            // Offset 0 above the start of the timeline can only be on a join, which is real progress (the earlier
            // file was played to its end): place it there so folding does not take it for 0.
            index -= 1;
            offset = old.durations().get(index);
        }
        Long file = old.fileIds().get(index);
        State inFile = new State(offset, state.watched(), state.watchedAt(), state.lastPlayedAt(), state.hidden(),
            state.hiddenAt());
        add(pieces, targetOfFile.get(file), new Piece(inFile, List.of(file), null, null, state.watched()));
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
        State rewatch = null;
        long rewatchAt = 0;
        for (Piece piece : pieces) {
            if (piece.rewatch()) {
                int at = orderedFileIds.indexOf(piece.fileIds().get(0));
                State candidate = piece.state();
                if (rewatch == null || later(candidate, rewatch)) {
                    rewatch = candidate;
                    rewatchAt = timeline.partStartMs(at) + candidate.positionMs();
                }
                continue;
            }
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
        State folded = StateFolding.fold(sources, timeline.totalMs());
        if (folded != null && folded.watched() && rewatch != null) {
            // Watched and being watched again: the flag and the position are both kept (folding alone would reset
            // the position to 0).
            folded = new State(Math.min(rewatchAt, timeline.totalMs()), true, folded.watchedAt(),
                folded.lastPlayedAt(), folded.hidden(), folded.hiddenAt());
        }
        return new Result(folded, marker);
    }

    private static boolean later(State a, State b) {
        return Comparator.nullsFirst(Comparator.<java.time.Instant>naturalOrder())
            .compare(a.lastPlayedAt(), b.lastPlayedAt()) > 0;
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
