package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.credits.CreditsFrameReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Turns one exported file and its Plex archive item into labelled stretches of the file: credits, film, and stretches
 * left out. Pure: it reads nothing and decides everything from its arguments. The rules are in
 * {@code docs/engineering/credits-classifier-plan.md}, "Labels" and "L1 specification".
 */
final class CreditsLabels {

    /** Frames this close to a merged credits boundary are left out (the fade, rounding and the copy shift). */
    static final long BOUNDARY_MS = 5_000;
    /** The largest shift between Plex's copy and CineFilm's, in milliseconds. */
    static final long MAX_SHIFT_MS = 4_000;
    /** The recomputed shift may differ from the stored one by this much. */
    static final long SHIFT_TOLERANCE_MS = 1;
    /** A first credits interval shorter than this, followed by a longer gap, is a leading island. */
    static final long ISLAND_MS = 60_000;
    /** The value of {@code presence} for a file that is in the library. */
    static final String PRESENT = "PRESENT";

    private CreditsLabels() {
    }

    /**
     * Labels one file.
     *
     * @param row the exported file
     * @param candidates the archive items that have this file (empty, one, or several)
     * @param ownerExcluded whether the owner's exclusions file lists the file
     * @return the label, or the first reason in the closed-set order that excludes the file. The split's
     *     {@code SPLIT_CONFLICT} is applied after this, by the caller.
     */
    static FileLabel label(ExportRow row, List<ArchiveMatch> candidates, boolean ownerExcluded) {
        if (!Boolean.TRUE.equals(row.lastOriginalPart())) {
            return FileLabel.excluded(ExcludeReason.NOT_LAST_PART);
        }
        if (!PRESENT.equals(row.presence())) {
            return FileLabel.excluded(ExcludeReason.NOT_PRESENT);
        }
        if (row.fingerprint() == null || !row.fingerprint().equals(row.plexFingerprint())) {
            return FileLabel.excluded(ExcludeReason.FINGERPRINT_CHANGED);
        }
        if (candidates.isEmpty()) {
            return FileLabel.excluded(ExcludeReason.NO_ARCHIVE_ITEM);
        }
        if (candidates.size() > 1) {
            return FileLabel.excluded(ExcludeReason.AMBIGUOUS_ARCHIVE_ITEM);
        }
        ArchiveMatch match = candidates.get(0);
        List<ArchiveItem.Marker> credits = match.item().markers().stream()
            .filter(m -> ArchiveItem.CREDITS.equals(m.type()) && m.startMs() != null).toList();
        if (credits.isEmpty()) {
            return FileLabel.excluded(ExcludeReason.NO_CREDITS_MARKER);
        }
        if (credits.stream().anyMatch(m -> m.endMs() == null)) {
            return FileLabel.excluded(ExcludeReason.MISSING_MARKER_END);
        }
        long earliest = credits.stream().mapToLong(ArchiveItem.Marker::startMs).min().orElseThrow();
        Long shift = shift(match);
        if (shift == null || Math.abs(shift - (row.plexStartMs() - earliest)) > SHIFT_TOLERANCE_MS) {
            return FileLabel.excluded(ExcludeReason.SHIFT_MISMATCH);
        }
        if (shift > MAX_SHIFT_MS) {
            return FileLabel.excluded(ExcludeReason.SHIFT_OUT_OF_RANGE);
        }
        long windowStart = row.durationMs() - CreditsFrameReader.windowMs(row.durationMs());
        List<Span> merged = merge(credits, shift);
        long target = merged.get(0).fromMs();
        if (target < windowStart) {
            return FileLabel.excluded(ExcludeReason.MARKER_BEFORE_WINDOW);
        }
        if (ownerExcluded) {
            return FileLabel.excluded(ExcludeReason.OWNER_EXCLUDED);
        }
        return stretches(merged, shift, windowStart, row.durationMs());
    }

    /**
     * The shift from Plex's copy onto this one, copied from {@code PlexCreditsRules.decide} (which is package-private
     * in {@code plex}): the matched version's duration minus the shortest of the item's own duration and every
     * version's duration, never below 0.
     *
     * @return the shift, or null when the item or any version has no duration
     */
    static Long shift(ArchiveMatch match) {
        Long item = match.item().durationMs();
        if (item == null) {
            return null;
        }
        long shortest = item;
        for (ArchiveItem.Media media : match.item().media()) {
            if (media.durationMs() == null) {
                return null;
            }
            shortest = Math.min(shortest, media.durationMs());
        }
        return Math.max(0, match.media().durationMs() - shortest);
    }

    /** The markers moved by the shift, overlapping, nested and touching ones merged, in order. */
    static List<Span> merge(List<ArchiveItem.Marker> markers, long shift) {
        List<Span> sorted = markers.stream()
            .map(m -> new Span(m.startMs() + shift, m.endMs() + shift))
            .sorted(Comparator.comparingLong(Span::fromMs).thenComparingLong(Span::toMs)).toList();
        List<Span> merged = new ArrayList<>();
        for (Span span : sorted) {
            int last = merged.size() - 1;
            if (last >= 0 && span.fromMs() <= merged.get(last).toMs()) {
                merged.set(last, new Span(merged.get(last).fromMs(), Math.max(merged.get(last).toMs(), span.toMs())));
            } else {
                merged.add(span);
            }
        }
        return merged;
    }

    private static FileLabel stretches(List<Span> merged, long shift, long windowStart, long duration) {
        TreeSet<Long> points = new TreeSet<>();
        points.add(windowStart);
        points.add(duration);
        for (Span span : merged) {
            for (long boundary : new long[] {span.fromMs(), span.toMs()}) {
                for (long point : new long[] {boundary - BOUNDARY_MS, boundary, boundary + BOUNDARY_MS}) {
                    points.add(Math.max(windowStart, Math.min(duration, point)));
                }
            }
        }
        long lastEnd = merged.get(merged.size() - 1).toMs();
        List<Span> credits = new ArrayList<>();
        List<Span> film = new ArrayList<>();
        List<LeftOut> leftOut = new ArrayList<>();
        Long from = null;
        for (long to : points) {
            if (from != null) {
                place(merged, lastEnd, from, to, credits, film, leftOut);
            }
            from = to;
        }
        long first = merged.get(0).fromMs();
        boolean island = merged.get(0).toMs() - first < ISLAND_MS && merged.size() > 1
            && merged.get(1).fromMs() - merged.get(0).toMs() > ISLAND_MS;
        return new FileLabel(null, first, shift, island, List.copyOf(credits), List.copyOf(film),
            List.copyOf(leftOut));
    }

    /** Puts the stretch {@code [from, to)}, which lies wholly in one category, into its list, joining its neighbour. */
    private static void place(List<Span> merged, long lastEnd, long from, long to, List<Span> credits,
                              List<Span> film, List<LeftOut> leftOut) {
        if (nearBoundary(merged, from)) {
            addLeftOut(leftOut, from, to, LeftOutReason.BOUNDARY);
        } else if (merged.stream().anyMatch(s -> from >= s.fromMs() && from < s.toMs())) {
            addSpan(credits, from, to);
        } else if (from >= lastEnd) {
            addLeftOut(leftOut, from, to, LeftOutReason.AFTER_LAST_CREDITS);
        } else {
            addSpan(film, from, to);
        }
    }

    private static boolean nearBoundary(List<Span> merged, long at) {
        return merged.stream().anyMatch(s -> within(at, s.fromMs()) || within(at, s.toMs()));
    }

    private static boolean within(long at, long boundary) {
        return at >= boundary - BOUNDARY_MS && at < boundary + BOUNDARY_MS;
    }

    private static void addSpan(List<Span> spans, long from, long to) {
        spans.add(new Span(from, to));
    }

    private static void addLeftOut(List<LeftOut> list, long from, long to, LeftOutReason reason) {
        int last = list.size() - 1;
        if (last >= 0 && list.get(last).toMs() == from && list.get(last).reason() == reason) {
            list.set(last, new LeftOut(list.get(last).fromMs(), to, reason));
        } else {
            list.add(new LeftOut(from, to, reason));
        }
    }
}
