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
        if (frames.size() < CreditsTuning.MOTION_MIN_FRAMES) {
            return NO_MOTION;
        }
        List<FrameClass> classes = new ArrayList<>(frames.size());
        for (GreyFrame frame : frames) {
            classes.add(FrameFeatures.measure(frame, area, peak).frameClass());
        }
        if (!mostlyText(classes)) {
            return NO_MOTION;
        }
        List<int[]> profiles = new ArrayList<>(frames.size());
        for (GreyFrame frame : frames) {
            profiles.add(rowProfile(frame, area, peak));
        }
        int crawlStart = crawlStart(profiles, classes, area.height());
        if (crawlStart >= 0) {
            return new Motion(Kind.CRAWL, crawlStart);
        }
        int cardsStart = cardsStart(frames, classes, area, peak);
        if (cardsStart >= 0) {
            return new Motion(Kind.CARDS, cardsStart);
        }
        return NO_MOTION;
    }

    private static boolean mostlyText(List<FrameClass> classes) {
        long text = classes.stream().filter(c -> c == FrameClass.TEXT_ON_DARK).count();
        long nonPicture = classes.stream().filter(c -> c != FrameClass.PICTURE).count();
        return nonPicture >= classes.size() * CreditsTuning.MOTION_MIN_NON_PICTURE_SHARE
            && text >= classes.size() * CreditsTuning.MOTION_MIN_TEXT_SHARE;
    }

    /** Bright pixels per row inside the area (against the window's peak), smoothed over three rows. */
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
        return smooth(profile);
    }

    /** Each count plus its neighbours above and below, so text moving by a fraction of a pixel still matches. */
    private static int[] smooth(int[] profile) {
        int[] smoothed = new int[profile.length];
        for (int row = 0; row < profile.length; row++) {
            smoothed[row] = profile[row] + (row > 0 ? profile[row - 1] : 0)
                + (row + 1 < profile.length ? profile[row + 1] : 0);
        }
        return smoothed;
    }

    /** The index of the first frame of the first steadily moving pair, or -1 when it is not a crawl. */
    private static int crawlStart(List<int[]> profiles, List<FrameClass> classes, int height) {
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
            boolean text = classes.get(i - 1) == FrameClass.TEXT_ON_DARK && classes.get(i) == FrameClass.TEXT_ON_DARK;
            if (shift > 0 && text) {
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

    /**
     * The index of the frame where the first card change lands, or -1 when it is not cards. A change counts only
     * between two text frames, with at most black cuts between them: going into or out of picture never does. Changes
     * on consecutive frames are one event (a fade or a crawl), not several cards. A pair only changes when at least
     * {@code CARD_CHANGE_MIN_SHARE} of the picture changed and those pixels are at least half of the larger frame's
     * bright pixels (above the same limit): a window switching or a figure stepping changes a fraction of the text.
     */
    private static int cardsStart(List<GreyFrame> frames, List<FrameClass> classes, ActiveArea area, int peak) {
        double limit = CreditsTuning.CARD_CHANGE_DIFF_FRACTION * peak;
        double pixels = (double) area.width() * area.height();
        int still = 0;
        for (int i = 1; i < frames.size(); i++) {
            if (changedPixels(frames.get(i - 1), frames.get(i), area, limit) / pixels
                <= CreditsTuning.CARD_STILL_MAX_SHARE) {
                still++;
            }
        }
        int changes = 0;
        int firstChange = -1;
        int previousText = -1;
        boolean lastChanged = false;
        for (int i = 0; i < frames.size(); i++) {
            FrameClass frameClass = classes.get(i);
            if (frameClass == FrameClass.PICTURE) {
                previousText = -1;
                lastChanged = false;
            } else if (frameClass == FrameClass.TEXT_ON_DARK) {
                if (previousText >= 0) {
                    long changedCount = changedPixels(frames.get(previousText), frames.get(i), area, limit);
                    long brightest = Math.max(brightPixels(frames.get(previousText), area, limit),
                        brightPixels(frames.get(i), area, limit));
                    boolean changed = changedCount / pixels >= CreditsTuning.CARD_CHANGE_MIN_SHARE
                        && changedCount >= CreditsTuning.CARD_CHANGE_TEXT_SHARE * brightest;
                    if (changed && !lastChanged) {
                        changes++;
                        if (firstChange < 0) {
                            firstChange = i;
                        }
                    }
                    lastChanged = changed;
                }
                previousText = i;
            }
        }
        boolean cards = changes >= CreditsTuning.CARD_MIN_CHANGES
            && still >= (frames.size() - 1) * CreditsTuning.CARD_MIN_STILL_SHARE;
        return cards ? firstChange : -1;
    }

    /** Pixels above the limit: the text a card change has to replace. */
    private static long brightPixels(GreyFrame frame, ActiveArea area, double limit) {
        long count = 0;
        for (int y = area.top(); y < area.top() + area.height(); y++) {
            for (int x = area.left(); x < area.left() + area.width(); x++) {
                if (frame.luma(x, y) > limit) {
                    count++;
                }
            }
        }
        return count;
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
