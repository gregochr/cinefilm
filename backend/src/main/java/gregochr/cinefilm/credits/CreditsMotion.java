package gregochr.cinefilm.credits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides whether a stretch of dense frames (two a second) looks like credits moving. Still text that never moves or
 * changes (an epilogue card, a starfield, candles) is neither kind.
 */
public final class CreditsMotion {

    /** The kind of movement seen. */
    public enum Kind {
        /** Rows of text shifting up by a steady number of pixels per frame. */
        CRAWL,
        /** Static text that changes abruptly at least twice. */
        CARDS,
        /** Neither. */
        NONE
    }

    /**
     * What was seen and where it begins.
     *
     * @param kind the kind of movement
     * @param startIndex the index in the analysed frames where the movement begins: the first frame of the first
     *     pair that moves at the crawl's steady shift, or the frame where the first card change lands; -1 for none
     */
    public record Motion(Kind kind, int startIndex) {
    }

    private static final Motion NO_MOTION = new Motion(Kind.NONE, -1);

    private CreditsMotion() {
    }

    /**
     * Looks for a crawl or for cards.
     *
     * @param frames consecutive frames, oldest first, all the same size
     * @param area the picture rectangle to measure in
     * @param peak the window's peak luma
     * @return the kind of movement, or {@link Kind#NONE}
     */
    public static Kind detect(List<GreyFrame> frames, ActiveArea area, int peak) {
        return analyse(frames, area, peak).kind();
    }

    /**
     * Looks for a crawl or for cards and says where the movement begins, so a still card held before it is never
     * counted.
     *
     * @param frames consecutive frames, oldest first, all the same size
     * @param area the picture rectangle to measure in
     * @param peak the window's peak luma
     * @return the kind of movement and the index where it begins
     */
    public static Motion analyse(List<GreyFrame> frames, ActiveArea area, int peak) {
        if (frames.size() < CreditsTuning.MOTION_MIN_FRAMES || !mostlyText(frames, area, peak)) {
            return NO_MOTION;
        }
        List<int[]> profiles = new ArrayList<>(frames.size());
        for (GreyFrame frame : frames) {
            profiles.add(rowProfile(frame, area, peak));
        }
        int crawlStart = crawlStart(profiles, area.height());
        if (crawlStart >= 0) {
            return new Motion(Kind.CRAWL, crawlStart);
        }
        int cardsStart = cardsStart(frames, area, peak);
        if (cardsStart >= 0) {
            return new Motion(Kind.CARDS, cardsStart);
        }
        return NO_MOTION;
    }

    private static boolean mostlyText(List<GreyFrame> frames, ActiveArea area, int peak) {
        int text = 0;
        int nonPicture = 0;
        for (GreyFrame frame : frames) {
            FrameClass frameClass = FrameFeatures.measure(frame, area, peak).frameClass();
            if (frameClass != FrameClass.PICTURE) {
                nonPicture++;
            }
            if (frameClass == FrameClass.TEXT_ON_DARK) {
                text++;
            }
        }
        return nonPicture >= frames.size() * CreditsTuning.MOTION_MIN_NON_PICTURE_SHARE
            && text >= frames.size() * CreditsTuning.MOTION_MIN_TEXT_SHARE;
    }

    /** Bright pixels per row inside the area. */
    private static int[] rowProfile(GreyFrame frame, ActiveArea area, int peak) {
        double brightLimit = CreditsTuning.BRIGHT_FRACTION * peak;
        int[] profile = new int[area.height()];
        for (int row = 0; row < area.height(); row++) {
            int count = 0;
            for (int x = area.left(); x < area.left() + area.width(); x++) {
                if (frame.luma(x, area.top() + row) > brightLimit) {
                    count++;
                }
            }
            profile[row] = count;
        }
        return profile;
    }

    /** The index of the first frame of the first steadily moving pair, or -1 when it is not a crawl. */
    private static int crawlStart(List<int[]> profiles, int height) {
        int maxShift = Math.max(1, (int) (height * CreditsTuning.CRAWL_MAX_SHIFT_SHARE));
        int informative = 0;
        List<Integer> shifts = new ArrayList<>();
        List<Integer> pairStarts = new ArrayList<>();
        for (int i = 1; i < profiles.size(); i++) {
            int[] before = profiles.get(i - 1);
            int[] after = profiles.get(i);
            if (sum(before) + sum(after) == 0) {
                continue;
            }
            informative++;
            int shift = matchingShift(before, after, maxShift);
            if (shift > 0) {
                shifts.add(shift);
                pairStarts.add(i - 1);
            }
        }
        if (shifts.isEmpty() || shifts.size() < informative * CreditsTuning.CRAWL_MIN_MOVING_SHARE) {
            return -1;
        }
        List<Integer> sorted = new ArrayList<>(shifts);
        Collections.sort(sorted);
        int median = sorted.get(sorted.size() / 2);
        long steady = shifts.stream().filter(s -> Math.abs(s - median) <= 1).count();
        if (steady < shifts.size() * CreditsTuning.CRAWL_STEADY_SHARE) {
            return -1;
        }
        int first = 0;
        while (Math.abs(shifts.get(first) - median) > 1) {
            first++;
        }
        return pairStarts.get(first);
    }

    /** The upward shift in pixels that turns {@code before} into {@code after}, or 0 when none does. */
    private static int matchingShift(int[] before, int[] after, int maxShift) {
        double still = relativeError(before, after, 0);
        int best = 0;
        double bestError = Double.MAX_VALUE;
        for (int shift = 1; shift <= maxShift; shift++) {
            double error = relativeError(before, after, shift);
            if (error < bestError) {
                bestError = error;
                best = shift;
            }
        }
        boolean matches = bestError <= CreditsTuning.CRAWL_MATCH_MAX_ERROR
            && bestError < CreditsTuning.CRAWL_BETTER_THAN_STILL * still;
        return matches ? best : 0;
    }

    /** How badly {@code after[y]} differs from {@code before[y + shift]}, from 0 (identical) to 1. */
    private static double relativeError(int[] before, int[] after, int shift) {
        long difference = 0;
        long total = 0;
        for (int y = 0; y + shift < before.length; y++) {
            difference += Math.abs(after[y] - before[y + shift]);
            total += after[y] + before[y + shift];
        }
        return total == 0 ? 1.0 : (double) difference / total;
    }

    /** The index of the frame where the first card change lands, or -1 when it is not cards. */
    private static int cardsStart(List<GreyFrame> frames, ActiveArea area, int peak) {
        double limit = CreditsTuning.CARD_CHANGE_DIFF_FRACTION * peak;
        double pixels = (double) area.width() * area.height();
        int pairs = frames.size() - 1;
        int still = 0;
        int changes = 0;
        boolean inChange = false;
        int firstChange = -1;
        for (int i = 1; i < frames.size(); i++) {
            double changed = changedPixels(frames.get(i - 1), frames.get(i), area, limit) / pixels;
            if (changed >= CreditsTuning.CARD_CHANGE_MIN_SHARE) {
                if (!inChange) {
                    changes++;
                }
                if (firstChange < 0) {
                    firstChange = i;
                }
                inChange = true;
            } else {
                inChange = false;
            }
            if (changed <= CreditsTuning.CARD_STILL_MAX_SHARE) {
                still++;
            }
        }
        boolean cards = changes >= CreditsTuning.CARD_MIN_CHANGES
            && still >= pairs * CreditsTuning.CARD_MIN_STILL_SHARE;
        return cards ? firstChange : -1;
    }

    private static long changedPixels(GreyFrame a, GreyFrame b, ActiveArea area, double limit) {
        long count = 0;
        for (int y = area.top(); y < area.top() + area.height(); y++) {
            for (int x = area.left(); x < area.left() + area.width(); x++) {
                if (Math.abs(a.luma(x, y) - b.luma(x, y)) > limit) {
                    count++;
                }
            }
        }
        return count;
    }

    private static long sum(int[] values) {
        long total = 0;
        for (int value : values) {
            total += value;
        }
        return total;
    }
}
