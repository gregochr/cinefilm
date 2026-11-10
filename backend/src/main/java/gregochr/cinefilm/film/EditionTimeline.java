package gregochr.cinefilm.film;

import java.util.List;

/**
 * The one continuous timeline of an edition whose film is split across several files. Every time the player, the
 * resume position, the credits marker or (later) a chapter or scrub thumbnail uses is an <em>edition time</em>: the
 * milliseconds from the start of part 1. This class converts between edition time and a position inside one part.
 * Pure: no Spring, no JPA.
 */
public final class EditionTimeline {

    private final long[] durations;
    private final long[] starts;
    private final long total;

    private EditionTimeline(List<Long> partDurationsMs) {
        if (partDurationsMs == null || partDurationsMs.isEmpty()) {
            throw new IllegalArgumentException("A timeline needs at least one part");
        }
        durations = new long[partDurationsMs.size()];
        starts = new long[partDurationsMs.size()];
        long sum = 0;
        for (int i = 0; i < durations.length; i++) {
            Long duration = partDurationsMs.get(i);
            if (duration == null || duration <= 0) {
                throw new IllegalArgumentException("Part " + (i + 1) + " needs a duration above 0, was " + duration);
            }
            durations[i] = duration;
            starts[i] = sum;
            sum += duration;
        }
        total = sum;
    }

    /** A position inside one part (0-based index) of an edition. */
    public record Position(int partIndex, long offsetMs) {
    }

    /**
     * Builds the timeline from the parts' durations in play order.
     *
     * @param partDurationsMs one duration per part, each above 0; at least one
     * @return the timeline
     * @throws IllegalArgumentException when there are no parts or a duration is null or not above 0
     */
    public static EditionTimeline of(List<Long> partDurationsMs) {
        return new EditionTimeline(partDurationsMs);
    }

    /**
     * Builds the timeline from an edition's {@link Edition#originalParts()}, each part as long as its video stream
     * ({@link MediaFile#timelineDurationMs()}) so a part starts where the previous part's picture ends.
     *
     * @param edition the edition
     * @return the timeline
     * @throws IllegalArgumentException when the edition has no original part or a part has no duration
     */
    public static EditionTimeline of(Edition edition) {
        return new EditionTimeline(edition.originalParts().stream().map(MediaFile::timelineDurationMs).toList());
    }

    /** The whole edition's length in milliseconds. */
    public long totalMs() {
        return total;
    }

    /** How many parts there are. */
    public int partCount() {
        return durations.length;
    }

    /**
     * Where a part starts on the edition timeline.
     *
     * @param partIndex the 0-based part
     * @return the start in milliseconds (0 for the first part)
     * @throws IllegalArgumentException when there is no such part
     */
    public long partStartMs(int partIndex) {
        checkPart(partIndex);
        return starts[partIndex];
    }

    /**
     * Finds the part and the offset inside it for an edition time. A negative time is the start of part 1; a time
     * exactly on a join belongs to the later part (offset 0); a time at or after the total is the last part at its full
     * duration.
     *
     * @param globalMs the edition time in milliseconds
     * @return the part and the offset inside it
     */
    public Position locate(long globalMs) {
        if (globalMs <= 0) {
            return new Position(0, 0);
        }
        int last = durations.length - 1;
        if (globalMs >= total) {
            return new Position(last, durations[last]);
        }
        int part = last;
        while (starts[part] > globalMs) {
            part--;
        }
        return new Position(part, globalMs - starts[part]);
    }

    /**
     * Converts an offset inside a part to edition time. An offset equal to the part's duration is allowed (it is
     * what {@link #locate} gives for the very end).
     *
     * @param partIndex the 0-based part
     * @param offsetMs the offset from the start of that part, 0 up to and including its duration
     * @return the edition time in milliseconds
     * @throws IllegalArgumentException when there is no such part or the offset lies outside it
     */
    public long toGlobal(int partIndex, long offsetMs) {
        checkPart(partIndex);
        if (offsetMs < 0 || offsetMs > durations[partIndex]) {
            throw new IllegalArgumentException(
                "Offset " + offsetMs + " is outside part " + (partIndex + 1) + " (0 to " + durations[partIndex] + ")");
        }
        return starts[partIndex] + offsetMs;
    }

    private void checkPart(int partIndex) {
        if (partIndex < 0 || partIndex >= durations.length) {
            throw new IllegalArgumentException(
                "No part " + partIndex + " in a timeline of " + durations.length + " parts");
        }
    }
}
