package gregochr.cinefilm.credits.learn;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the nine dense frames whose stills the measurement tool writes: the ones nearest to the target start minus
 * 30, 20, 10 and 5 seconds, at the target, and plus 5, 10, 20 and 30 seconds. Pure.
 */
final class StillsPicker {

    /** The seconds from the target, in the order the stills and the contact sheet use. */
    static final List<Integer> SECONDS = List.of(-30, -20, -10, -5, 0, 5, 10, 20, 30);

    private StillsPicker() {
    }

    /**
     * One chosen frame.
     *
     * @param seconds the seconds from the target it stands for
     * @param offsetMs the frame's offset in the file
     * @param fromTargetMs the frame's offset minus the target start
     */
    record Pick(int seconds, long offsetMs, long fromTargetMs) {

        /**
         * The name the still's files share, without extension: {@code d-30s}, {@code d+0s}, {@code d+5s}.
         *
         * @return the base name
         */
        String baseName() {
            return "d" + (seconds < 0 ? "-" : "+") + Math.abs(seconds) + "s";
        }
    }

    /**
     * Picks the frames: for each of the nine times, the dense frame whose distance from it is smallest, the earlier
     * frame winning a tie. A frame can be picked for more than one time (when the dense range was cut short).
     *
     * @param denseOffsets the offsets of the dense frames
     * @param targetStartMs the target start
     * @return the nine picks in {@link #SECONDS} order; empty when there are no dense frames
     */
    static List<Pick> pick(List<Long> denseOffsets, long targetStartMs) {
        List<Pick> picks = new ArrayList<>();
        if (denseOffsets.isEmpty()) {
            return picks;
        }
        for (int seconds : SECONDS) {
            long wanted = seconds * 1000L;
            long best = 0;
            long bestDistance = Long.MAX_VALUE;
            for (long offset : denseOffsets) {
                long distance = Math.abs(offset - targetStartMs - wanted);
                if (distance < bestDistance || distance == bestDistance && offset < best) {
                    best = offset;
                    bestDistance = distance;
                }
            }
            picks.add(new Pick(seconds, best, best - targetStartMs));
        }
        return picks;
    }
}
