package gregochr.cinefilm.transcode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The segment timeline of one film, shared by every rung: segment {@code n} is {@code [startMs(n), startMs(n+1))}
 * and the last one ends at the film's duration. The first cut is 0 and each next cut is the first keyframe at or
 * after the previous cut plus {@link #TARGET_MS}, so the list is strictly increasing however far apart keyframes
 * are. A film split across several files has one list per file and {@link #join} puts them on one timeline: each
 * part's cuts shifted by the part's start, with a cut at every part start. Immutable.
 */
public final class CutList {

    /** The shortest a segment is made when keyframes allow it. */
    public static final long TARGET_MS = 4000;

    /** A file whose longest segment exceeds this gets no copy rung. */
    public static final long MAX_COPY_GAP_MS = 12_000;

    private final long[] cuts;
    private final long durationMs;
    private final long maxGapMs;
    /** The segment each part starts at ({@code {0}} for a list that was not joined). */
    private final int[] partStartSegments;
    /** The time each part starts at ({@code {0}} for a list that was not joined). */
    private final long[] partStartsMs;
    /** The per-part lists of a joined list; null when this list is a single file's. */
    private final List<CutList> parts;

    private CutList(long[] cuts, long durationMs) {
        this(cuts, durationMs, new int[] {0}, new long[] {0}, null);
    }

    private CutList(long[] cuts, long durationMs, int[] partStartSegments, long[] partStartsMs,
                    List<CutList> parts) {
        this.cuts = cuts;
        this.durationMs = durationMs;
        this.partStartSegments = partStartSegments;
        this.partStartsMs = partStartsMs;
        this.parts = parts;
        long widest = durationMs - cuts[cuts.length - 1];
        for (int i = 1; i < cuts.length; i++) {
            widest = Math.max(widest, cuts[i] - cuts[i - 1]);
        }
        this.maxGapMs = widest;
    }

    /**
     * Builds the cuts from a film's keyframes.
     *
     * @param keyframesMs video keyframe presentation times in milliseconds (any order; duplicates are ignored)
     * @param durationMs the film's duration, greater than 0
     * @return the cut list; with no keyframes it is the single cut 0
     */
    public static CutList fromKeyframes(List<Long> keyframesMs, long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("duration must be positive");
        }
        long[] sorted = keyframesMs.stream().mapToLong(Long::longValue).sorted().toArray();
        List<Long> cuts = new ArrayList<>();
        cuts.add(0L);
        long last = 0;
        for (long keyframe : sorted) {
            if (keyframe >= last + TARGET_MS && keyframe < durationMs) {
                cuts.add(keyframe);
                last = keyframe;
            }
        }
        return new CutList(cuts.stream().mapToLong(Long::longValue).toArray(), durationMs);
    }

    /**
     * Reads the comma-separated text stored in {@code media_keyframes.cuts}.
     *
     * @param text segment start times in milliseconds, for example {@code 0,4004,8008}
     * @param durationMs the film's duration, greater than 0
     * @return the cut list
     * @throws KeyframeIndexException when the text is not a strictly increasing list starting at 0 and below the
     *     duration
     */
    public static CutList parse(String text, long durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("duration must be positive");
        }
        String[] parts = text.split(",", -1);
        long[] cuts = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                cuts[i] = Long.parseLong(parts[i]);
            }
        } catch (NumberFormatException e) {
            throw new KeyframeIndexException("Stored cuts are not numbers", e);
        }
        if (cuts[0] != 0) {
            throw new KeyframeIndexException("Stored cuts do not start at 0");
        }
        for (int i = 1; i < cuts.length; i++) {
            if (cuts[i] <= cuts[i - 1]) {
                throw new KeyframeIndexException("Stored cuts are not increasing");
            }
        }
        if (cuts[cuts.length - 1] >= durationMs) {
            throw new KeyframeIndexException("Stored cuts run past the duration");
        }
        return new CutList(cuts, durationMs);
    }

    /**
     * Puts the cut lists of a film's parts, in play order, on one timeline. Part {@code i} starts where the parts
     * before it end (the sum of their {@link #totalDurationMs()}), its cuts are shifted by that start, and the start
     * itself is a cut, so a segment never spans two files. Segment numbers run on across parts: part 0's segments
     * come first, then part 1's.
     *
     * @param partCuts one list per part, at least one; none of them may itself be joined
     * @return the joined list; one part gives an equal list that still knows its single part
     * @throws IllegalArgumentException when there are no parts
     */
    public static CutList join(List<CutList> partCuts) {
        if (partCuts.isEmpty()) {
            throw new IllegalArgumentException("at least one part");
        }
        List<Long> all = new ArrayList<>();
        int[] segments = new int[partCuts.size()];
        long[] starts = new long[partCuts.size()];
        long start = 0;
        for (int p = 0; p < partCuts.size(); p++) {
            CutList part = partCuts.get(p);
            if (part.parts != null) {
                throw new IllegalArgumentException("a joined list cannot be joined again");
            }
            segments[p] = all.size();
            starts[p] = start;
            for (long cut : part.cuts) {
                all.add(start + cut);
            }
            start += part.durationMs;
        }
        return new CutList(all.stream().mapToLong(Long::longValue).toArray(), start, segments, starts,
            List.copyOf(partCuts));
    }

    /** How many parts the film has (1 unless the list was made by {@link #join}). */
    public int partCount() {
        return partStartSegments.length;
    }

    /**
     * The cut list of one part, on that file's own timeline (0 is the start of the file).
     *
     * @throws IndexOutOfBoundsException when there is no such part
     */
    public CutList part(int part) {
        checkPart(part);
        return parts == null ? this : parts.get(part);
    }

    /**
     * The first segment of a part; segment {@code partStartSegment(p)} starts at {@link #partStartMs(int)}.
     *
     * @throws IndexOutOfBoundsException when there is no such part
     */
    public int partStartSegment(int part) {
        checkPart(part);
        return partStartSegments[part];
    }

    /** The first segment of every part, in order; the first is always 0. */
    public List<Integer> partStartSegments() {
        return Arrays.stream(partStartSegments).boxed().toList();
    }

    /**
     * Where a part starts on the film's timeline in milliseconds.
     *
     * @throws IndexOutOfBoundsException when there is no such part
     */
    public long partStartMs(int part) {
        checkPart(part);
        return partStartsMs[part];
    }

    /** The last segment of a part. */
    public int lastSegmentOfPart(int part) {
        checkPart(part);
        return part + 1 < partStartSegments.length ? partStartSegments[part + 1] - 1 : cuts.length - 1;
    }

    /**
     * The part that segment {@code n} belongs to.
     *
     * @throws IndexOutOfBoundsException when {@code n} is not a segment of this list
     */
    public int partOfSegment(int n) {
        checkSegment(n);
        int index = Arrays.binarySearch(partStartSegments, n);
        return index >= 0 ? index : -index - 2;
    }

    private void checkPart(int part) {
        if (part < 0 || part >= partStartSegments.length) {
            throw new IndexOutOfBoundsException("No part " + part + " in " + partStartSegments.length);
        }
    }

    /** The text to store: segment start times in milliseconds, comma-separated. */
    public String toText() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < cuts.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(cuts[i]);
        }
        return text.toString();
    }

    /** How many segments the film has (at least 1). */
    public int segmentCount() {
        return cuts.length;
    }

    /**
     * Start of segment {@code n} in milliseconds.
     *
     * @throws IndexOutOfBoundsException when {@code n} is not a segment of this list
     */
    public long startMs(int n) {
        checkSegment(n);
        return cuts[n];
    }

    /**
     * Length of segment {@code n} in milliseconds (the last one runs to the film's duration).
     *
     * @throws IndexOutOfBoundsException when {@code n} is not a segment of this list
     */
    public long durationMs(int n) {
        checkSegment(n);
        long end = n + 1 < cuts.length ? cuts[n + 1] : durationMs;
        return end - cuts[n];
    }

    private void checkSegment(int n) {
        if (n < 0 || n >= cuts.length) {
            throw new IndexOutOfBoundsException("No segment " + n + " in " + cuts.length);
        }
    }

    /** The film's duration in milliseconds. */
    public long totalDurationMs() {
        return durationMs;
    }

    /** The longest segment in milliseconds, the final one included. */
    public long maxGapMs() {
        return maxGapMs;
    }

    /** True when the longest segment is within {@link #MAX_COPY_GAP_MS}, so the original can be copied. */
    public boolean copyOk() {
        return maxGapMs <= MAX_COPY_GAP_MS;
    }

    /** The index of the segment that contains {@code positionMs} (clamped to the first and last segment). */
    public int segmentAt(long positionMs) {
        int index = Arrays.binarySearch(cuts, positionMs);
        if (index >= 0) {
            return index;
        }
        return Math.max(0, -index - 2);
    }
}
