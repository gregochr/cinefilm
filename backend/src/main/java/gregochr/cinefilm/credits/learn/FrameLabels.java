package gregochr.cinefilm.credits.learn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The lit-picture rules on top of L1's labels (plan, "{@code FrameLabels} and {@code CreditsFeaturesMain}"). Pure.
 *
 * <p>For each credits interval of a record: {@code LIT_OPENING} leaves out the stretch before the first two
 * consecutive plain coarse frames (the whole interval when there is no such pair); then {@code LIT_RUN} leaves out
 * each run of three or more consecutive lit coarse frames. Membership is half-open, and "consecutive" means adjacent
 * entries of the coarse list.
 */
public final class FrameLabels {

    /** A run of lit frames is left out from this many frames (plan, {@code LIT_RUN}). */
    static final int MIN_LIT_RUN = 3;
    /** A run is cut this long after its last frame, or at the interval's end (plan, {@code LIT_RUN}). */
    static final long RUN_TAIL_MS = 5_000;

    private FrameLabels() {
    }

    /**
     * Applies the lit rules to one record.
     *
     * @param record the L1 record
     * @param coarse the film's coarse frames' kinds, sorted by offset
     * @return the record with the credits shrunk, the lit stretches left out, and the opening flags set; an excluded
     *     record keeps its empty lists and gets null flags
     */
    public static FrameLabelRecord apply(LabelRecord record, List<KindAt> coarse) {
        if (record.excluded() != null) {
            return build(record, record.credits(), record.leftOut(), null);
        }
        List<Span> credits = new ArrayList<>();
        List<LeftOut> leftOut = new ArrayList<>(record.leftOut());
        for (Span interval : record.credits()) {
            applyTo(interval, coarse, credits, leftOut);
        }
        Optional<FrameKind> type = OpeningType.of(coarse, record.targetStartMs());
        return build(record, credits, mergeAdjacent(leftOut), type.orElse(null));
    }

    /**
     * The label of any frame, coarse or dense.
     *
     * @param record a record from {@link #apply}
     * @param offsetMs the frame's offset in ms
     * @return credits, film, or the reason of the left-out interval holding it, by half-open membership
     * @throws IllegalArgumentException when the offset is outside the union of the three lists (first {@code from_ms}
     *     to last {@code to_ms}), or the record is excluded (its lists are empty)
     */
    public static FrameLabel labelAt(FrameLabelRecord record, long offsetMs) {
        for (Span span : record.credits()) {
            if (inside(span.fromMs(), span.toMs(), offsetMs)) {
                return FrameLabel.CREDITS;
            }
        }
        for (Span span : record.film()) {
            if (inside(span.fromMs(), span.toMs(), offsetMs)) {
                return FrameLabel.FILM;
            }
        }
        for (LeftOut out : record.leftOut()) {
            if (inside(out.fromMs(), out.toMs(), offsetMs)) {
                return FrameLabel.leftOut(out.reason());
            }
        }
        throw new IllegalArgumentException("Offset " + offsetMs + " ms is outside the labelled window of file "
            + record.mediaFileId());
    }

    private static boolean inside(long from, long to, long at) {
        return from <= at && at < to;
    }

    private static void applyTo(Span interval, List<KindAt> coarse, List<Span> credits, List<LeftOut> leftOut) {
        List<KindAt> frames = coarse.stream().filter(k -> inside(interval.fromMs(), interval.toMs(), k.offsetMs()))
            .toList();
        int pair = firstPlainPair(frames);
        if (pair < 0) {
            leftOut.add(new LeftOut(interval.fromMs(), interval.toMs(), LeftOutReason.LIT_OPENING));
            return;
        }
        long keepFrom = interval.fromMs();
        if (pair > 0) {
            keepFrom = frames.get(pair).offsetMs();
            leftOut.add(new LeftOut(interval.fromMs(), keepFrom, LeftOutReason.LIT_OPENING));
        }
        int i = pair;
        while (i < frames.size()) {
            if (frames.get(i).kind() != FrameKind.LIT) {
                i++;
                continue;
            }
            int end = i;
            while (end + 1 < frames.size() && frames.get(end + 1).kind() == FrameKind.LIT) {
                end++;
            }
            if (end - i + 1 >= MIN_LIT_RUN) {
                // A run that starts inside the previous run's 5 s tail (closely spaced frames) starts where that ended,
                // so the lists stay disjoint; the touching stretches are then joined.
                long from = Math.max(frames.get(i).offsetMs(), keepFrom);
                long to = Math.min(frames.get(end).offsetMs() + RUN_TAIL_MS, interval.toMs());
                addCredits(credits, keepFrom, from);
                if (to > from) {
                    leftOut.add(new LeftOut(from, to, LeftOutReason.LIT_RUN));
                    keepFrom = to;
                }
            }
            i = end + 1;
        }
        addCredits(credits, keepFrom, interval.toMs());
    }

    private static int firstPlainPair(List<KindAt> frames) {
        for (int i = 0; i + 1 < frames.size(); i++) {
            if (plain(frames.get(i)) && plain(frames.get(i + 1))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean plain(KindAt frame) {
        return frame.kind() != FrameKind.LIT;
    }

    private static void addCredits(List<Span> credits, long from, long to) {
        if (to > from) {
            credits.add(new Span(from, to));
        }
    }

    /** Sorts by {@code from_ms} and joins touching stretches of one reason, as L1 does. */
    private static List<LeftOut> mergeAdjacent(List<LeftOut> leftOut) {
        List<LeftOut> sorted = leftOut.stream().sorted(Comparator.comparingLong(LeftOut::fromMs)).toList();
        List<LeftOut> merged = new ArrayList<>();
        for (LeftOut out : sorted) {
            int last = merged.size() - 1;
            if (last >= 0 && merged.get(last).toMs() == out.fromMs() && merged.get(last).reason() == out.reason()) {
                merged.set(last, new LeftOut(merged.get(last).fromMs(), out.toMs(), out.reason()));
            } else {
                merged.add(out);
            }
        }
        return merged;
    }

    private static FrameLabelRecord build(LabelRecord r, List<Span> credits, List<LeftOut> leftOut, FrameKind type) {
        Boolean lit = type == null ? null : type == FrameKind.LIT;
        return new FrameLabelRecord(r.mediaFileId(), r.fingerprint(), r.unitKey(), r.side(), r.fold(),
            r.targetStartMs(), r.shiftMs(), r.leadingIsland(), List.copyOf(credits), r.film(), List.copyOf(leftOut),
            r.excluded(), r.excludedNote(), type, lit);
    }
}
