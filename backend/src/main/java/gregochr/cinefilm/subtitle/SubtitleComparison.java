package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares the forced subtitle an edition serves with OpenSubtitles' file for the film. Pure: cues in, a result out.
 *
 * <p>The lines are not compared (they may be worded differently); the cue <em>start times</em> are. Candidate offsets
 * come from the differences between a served cue's start and a reference cue's start (within ten minutes either way),
 * bucketed to 100 ms; the {@value #MAX_CANDIDATES} most frequent buckets are tried. An offset is scored by the share
 * of served cues whose shifted start lands within 2 seconds of some reference cue's start. The best offset is the one
 * with the most matches; among equals the one with the smallest total distance to the cues it matched (so a constant
 * shift is found exactly, not hidden inside the 2 second window), then the smallest absolute offset, then the lower
 * offset.
 *
 * <p>The verdict is {@link Verdict#CHECK} when under 70% of the served cues match, when the served file has fewer than
 * half the reference's cues (incomplete), or more than twice as many (probably full subtitles, not forced); otherwise
 * {@link Verdict#AGREES}. The best offset is kept whatever the verdict and is only ever a hint.
 */
public final class SubtitleComparison {

    /** The largest offset considered, either way, in milliseconds (ten minutes). */
    static final long MAX_OFFSET_MS = 600_000;
    /** Offsets are bucketed to this many milliseconds. */
    static final long BUCKET_MS = 100;
    /** A served cue matches when its shifted start is this close to a reference cue's start (inclusive). */
    static final long WINDOW_MS = 2000;
    /** The most candidate offsets tried. */
    static final int MAX_CANDIDATES = 500;
    /** An offset at least this large is mentioned as a hint (inclusive). */
    static final long HINT_MS = 500;

    private SubtitleComparison() {
    }

    /** The verdict of a comparison. */
    public enum Verdict {
        /** The served file looks like OpenSubtitles' file. */
        AGREES,
        /** The served file is incomplete, for another cut, or not forced-only: the owner should look. */
        CHECK
    }

    /**
     * The outcome.
     *
     * @param servedCount cues in the served subtitle
     * @param referenceCount cues in OpenSubtitles' file
     * @param matchedShare the share of served cues (0 to 1) that line up at the best offset; 0 when there are none
     * @param bestOffsetMs the milliseconds to add to the served cues to line up best, a multiple of 100 (0 when
     *     nothing could be compared)
     * @param verdict {@link Verdict#AGREES} or {@link Verdict#CHECK}
     * @param reasons plain sentences for the owner: why it is {@code CHECK}, and the timing hint when the best offset
     *     is half a second or more
     */
    public record Result(int servedCount, int referenceCount, double matchedShare, int bestOffsetMs, Verdict verdict,
                         List<String> reasons) {
    }

    /** An offset and how well it lines the files up. */
    private record Score(long offsetMs, int matched, long distance) {
    }

    /**
     * Compares the served cues with OpenSubtitles' cues.
     *
     * @param served the cues playback serves for the edition, on the edition's timeline
     * @param reference OpenSubtitles' cues, on the whole film's timeline
     * @return the result
     */
    public static Result compare(List<SubtitleCue> served, List<SubtitleCue> reference) {
        return compare(served, reference, MAX_CANDIDATES);
    }

    static Result compare(List<SubtitleCue> served, List<SubtitleCue> reference, int maxCandidates) {
        int servedCount = served.size();
        int referenceCount = reference.size();
        if (referenceCount == 0) {
            return new Result(servedCount, 0, 0.0, 0, Verdict.CHECK,
                List.of("OpenSubtitles' file has no lines to compare with"));
        }
        long[] s = starts(served);
        long[] r = starts(reference);
        Score best = servedCount == 0 ? new Score(0, 0, 0) : best(s, r, maxCandidates);
        double share = servedCount == 0 ? 0.0 : (double) best.matched() / servedCount;
        List<String> reasons = new ArrayList<>();
        if (servedCount > 0 && best.matched() * 100L < servedCount * 70L) {
            long percent = best.matched() * 100L / servedCount;
            reasons.add("Only " + percent + "% of this file's lines line up with OpenSubtitles' (within 2 seconds)");
        }
        if (servedCount * 2L < referenceCount) {
            reasons.add("OpenSubtitles has " + referenceCount + (referenceCount == 1 ? " line" : " lines")
                + ", this file has " + servedCount);
        }
        if (servedCount > referenceCount * 2L) {
            reasons.add("This file has " + servedCount + " lines, OpenSubtitles has " + referenceCount
                + ": probably full subtitles, not forced");
        }
        Verdict verdict = reasons.isEmpty() ? Verdict.AGREES : Verdict.CHECK;
        long offset = best.offsetMs();
        if (Math.abs(offset) >= HINT_MS) {
            reasons.add("OpenSubtitles' file is " + String.format(Locale.ROOT, "%.1f", Math.abs(offset) / 1000.0)
                + " s " + (offset > 0 ? "later" : "earlier"));
        }
        return new Result(servedCount, referenceCount, share, (int) offset, verdict, List.copyOf(reasons));
    }

    private static long[] starts(List<SubtitleCue> cues) {
        long[] starts = cues.stream().mapToLong(SubtitleCue::startMs).toArray();
        Arrays.sort(starts);
        return starts;
    }

    /** The best-scoring of the candidate offsets (there is always at least one when both sides have cues). */
    private static Score best(long[] s, long[] r, int maxCandidates) {
        Score best = null;
        for (long offset : candidates(s, r, maxCandidates)) {
            Score score = score(s, r, offset);
            if (best == null || BEST_FIRST.compare(score, best) < 0) {
                best = score;
            }
        }
        // No two cues were within ten minutes of each other: nothing lines up at any offset.
        return best == null ? score(s, r, 0) : best;
    }

    /** Most matches, then the least total distance, then the smaller absolute offset, then the lower offset. */
    private static final Comparator<Score> BEST_FIRST = Comparator.comparingInt(Score::matched).reversed()
        .thenComparingLong(Score::distance).thenComparingLong(score -> Math.abs(score.offsetMs()))
        .thenComparingLong(Score::offsetMs);

    /**
     * The offsets worth trying: the differences {@code reference - served} within ten minutes either way, rounded to
     * 100 ms, the most frequent first (ties: the smaller absolute offset, then the lower), at most {@code max}.
     */
    static List<Long> candidates(long[] s, long[] r, int max) {
        Map<Long, Integer> counts = new HashMap<>();
        for (long start : s) {
            int from = lowerBound(r, start - MAX_OFFSET_MS - BUCKET_MS / 2);
            for (int j = from; j < r.length && r[j] - start <= MAX_OFFSET_MS + BUCKET_MS / 2; j++) {
                long bucket = Math.floorDiv(r[j] - start + BUCKET_MS / 2, BUCKET_MS) * BUCKET_MS;
                if (Math.abs(bucket) <= MAX_OFFSET_MS) {
                    counts.merge(bucket, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
            .sorted(Map.Entry.<Long, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparingLong(e -> Math.abs(e.getKey())).thenComparingLong(Map.Entry::getKey))
            .limit(max).map(Map.Entry::getKey).toList();
    }

    /** The index of the first element that is at least {@code value}. */
    private static int lowerBound(long[] sorted, long value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** How many served cues land within the window of a reference start after the offset, and how far off they are. */
    private static Score score(long[] s, long[] r, long offset) {
        int matched = 0;
        long distance = 0;
        int j = 0;
        for (long start : s) {
            long shifted = start + offset;
            // The reference start at or after the shifted start, and the one before it: the nearest is one of them.
            while (j < r.length && r[j] < shifted) {
                j++;
            }
            long nearest = Long.MAX_VALUE;
            if (j < r.length) {
                nearest = r[j] - shifted;
            }
            if (j > 0) {
                nearest = Math.min(nearest, shifted - r[j - 1]);
            }
            if (nearest <= WINDOW_MS) {
                matched++;
                distance += nearest;
            }
        }
        return new Score(offset, matched, distance);
    }
}
