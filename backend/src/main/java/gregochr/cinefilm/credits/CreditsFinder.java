package gregochr.cinefilm.credits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds where the end credits start, in two pure steps that phase 1b feeds with real frames:
 *
 * <ol>
 *   <li>{@link #findCandidate} walks back from the end of the window over coarse classified samples (one every
 *       5 seconds) and finds the run of black and text on a dark screen that could be the credits.</li>
 *   <li>{@link #refine} looks at dense frames (two a second) from just before that candidate, confirms the text moves,
 *       places the start to the half second and checks it against the dialogue when the cue times are known.</li>
 * </ol>
 *
 * Every rule is biased to "late or nothing".
 */
public final class CreditsFinder {

    private CreditsFinder() {
    }

    /**
     * Step 1: the candidate start of the credits run. Walking back from the end, picture gaps of up to 120 seconds
     * are allowed (mid- and post-credits scenes); the run must last at least 60 seconds with at least 30 seconds of
     * text on dark. A single leading card followed by picture ("Ten years later") is not part of the run.
     *
     * @param samples the coarse samples, one every 5 seconds
     * @param windowEndMs the end of the window (normally the end of the file)
     * @return the candidate, or the {@code NOT_FOUND} result saying why not
     */
    public static CoarseOutcome findCandidate(List<CoarseSample> samples, long windowEndMs) {
        List<CoarseSample> sorted = samples.stream().sorted(Comparator.comparingLong(CoarseSample::offsetMs)).toList();
        int last = -1;
        for (int i = sorted.size() - 1; i >= 0 && last < 0; i--) {
            if (isCreditsLike(sorted.get(i))) {
                last = i;
            }
        }
        if (last < 0) {
            return rejected("The ending has no black screen and no text on a dark screen");
        }
        long trailingPicture = windowEndMs - (sorted.get(last).offsetMs() + CreditsTuning.SAMPLE_MS);
        if (trailingPicture > CreditsTuning.MAX_PICTURE_GAP_MS) {
            return rejected("The last " + duration(trailingPicture) + " of the film is picture, not credits");
        }
        int first = last;
        for (int i = last - 1; i >= 0; i--) {
            if (!isCreditsLike(sorted.get(i))) {
                continue;
            }
            long gap = sorted.get(first).offsetMs() - (sorted.get(i).offsetMs() + CreditsTuning.SAMPLE_MS);
            if (gap > CreditsTuning.MAX_PICTURE_GAP_MS) {
                break;
            }
            first = i;
        }
        first = skipLeadingCards(sorted, first, last);

        long spanMs = sorted.get(last).offsetMs() + CreditsTuning.SAMPLE_MS - sorted.get(first).offsetMs();
        if (spanMs < CreditsTuning.MIN_RUN_MS) {
            return rejected("Black or text on a dark screen lasts only " + duration(spanMs)
                + ", and credits last at least a minute");
        }
        long textMs = 0;
        for (int i = first; i <= last; i++) {
            if (sorted.get(i).frameClass() == FrameClass.TEXT_ON_DARK) {
                textMs += CreditsTuning.SAMPLE_MS;
            }
        }
        if (textMs < CreditsTuning.MIN_TEXT_MS) {
            return rejected("The ending is mostly black, with only " + duration(textMs) + " of text on a dark screen");
        }
        return new CoarseOutcome.Candidate(sorted.get(first).offsetMs());
    }

    /**
     * Step 2: confirms the candidate and places the start. A crawl or cards must begin within the dense frames
     * (30 second windows tried every 5 seconds from the first text on dark); the start is where that movement begins,
     * never an earlier still card. At most 10 seconds of black right before it count as the start (the fade). Then,
     * when the film's English cue starts are known, dialogue continuing more than 30 seconds past the start (cues
     * after a 120 second silence ignored) makes the result unsure.
     *
     * @param candidate the coarse step's candidate
     * @param dense frames at two a second from 10 seconds before the candidate, oldest first
     * @param area the picture rectangle
     * @param peak the window's peak luma
     * @param cueStartsMs the cue start offsets (ms in the file) of the English subtitle track, or null when unknown
     * @return the verdict with its offset (to the half second) and reasons
     */
    public static CreditsResult refine(CoarseOutcome.Candidate candidate, List<DenseFrame> dense, ActiveArea area,
                                       int peak, List<Long> cueStartsMs) {
        List<DenseFrame> frames = dense.stream().sorted(Comparator.comparingLong(DenseFrame::offsetMs)).toList();
        List<FrameClass> classes = new ArrayList<>(frames.size());
        for (DenseFrame frame : frames) {
            classes.add(FrameFeatures.measure(frame.frame(), area, peak).frameClass());
        }
        int from = firstIndex(frames, classes, candidate.startMs() - CreditsTuning.SAMPLE_MS);
        int textAt = -1;
        for (int i = Math.max(from, 0); from >= 0 && i < frames.size() && textAt < 0; i++) {
            if (classes.get(i) == FrameClass.TEXT_ON_DARK) {
                textAt = i;
            }
        }
        if (textAt < 0) {
            return CreditsResult.unsure(candidate.startMs(),
                "Looking closely at the start of the credits found no text on a dark screen");
        }
        CreditsMotion.Motion motion = null;
        int motionAt = -1;
        for (int windowStart = textAt; windowStart < frames.size() && motion == null;
             windowStart += CreditsTuning.MOTION_WINDOW_STEP_FRAMES) {
            long windowMs = frames.get(windowStart).offsetMs();
            List<GreyFrame> window = new ArrayList<>();
            for (int i = windowStart; i < frames.size()
                && frames.get(i).offsetMs() < windowMs + CreditsTuning.MOTION_WINDOW_MS; i++) {
                window.add(frames.get(i).frame());
            }
            CreditsMotion.Motion seen = CreditsMotion.analyse(window, area, peak);
            if (seen.kind() != CreditsMotion.Kind.NONE) {
                motion = seen;
                motionAt = windowStart + seen.startIndex();
            }
        }
        if (motion == null) {
            return CreditsResult.unsure(candidate.startMs(), "Text on a dark screen, but it never moves");
        }

        long motionMs = frames.get(motionAt).offsetMs();
        int black = motionAt;
        while (black - 1 >= from && classes.get(black - 1) == FrameClass.BLACK) {
            black--;
        }
        long startMs = motionMs - frames.get(black).offsetMs() <= CreditsTuning.MAX_LEADING_BLACK_MS
            ? frames.get(black).offsetMs() : motionMs;

        long lastDialogue = lastDialogueMs(startMs, cueStartsMs);
        if (lastDialogue - startMs > CreditsTuning.MAX_DIALOGUE_AFTER_START_MS) {
            return CreditsResult.unsure(startMs, "Someone is still talking " + duration(lastDialogue - startMs)
                + " after the credits seem to start");
        }
        String what = motion.kind() == CreditsMotion.Kind.CRAWL
            ? "Text on a dark screen moves up steadily from " + clock(startMs)
            : "Cards of text on a dark screen change from " + clock(startMs);
        return CreditsResult.found(startMs, what);
    }

    private static boolean isCreditsLike(CoarseSample sample) {
        return sample.frameClass() != FrameClass.PICTURE;
    }

    /** Drops a single black or text sample followed by picture from the front of the run. */
    private static int skipLeadingCards(List<CoarseSample> samples, int first, int last) {
        int start = first;
        while (start < last && !isCreditsLike(samples.get(start + 1))) {
            int next = start + 1;
            while (!isCreditsLike(samples.get(next))) {
                next++;
            }
            start = next;
        }
        return start;
    }

    /** The first non-picture dense frame at or after the offset, or -1. */
    private static int firstIndex(List<DenseFrame> frames, List<FrameClass> classes, long fromMs) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).offsetMs() >= fromMs && classes.get(i) != FrameClass.PICTURE) {
                return i;
            }
        }
        return -1;
    }

    /** The last cue that still belongs to the conversation running on from the start, or the start itself. */
    private static long lastDialogueMs(long startMs, List<Long> cueStartsMs) {
        if (cueStartsMs == null) {
            return startMs;
        }
        long last = startMs;
        for (long cue : cueStartsMs.stream().sorted().toList()) {
            if (cue < startMs) {
                continue;
            }
            if (cue - last > CreditsTuning.DIALOGUE_GAP_MS) {
                break;
            }
            last = cue;
        }
        return last;
    }

    private static CoarseOutcome rejected(String reason) {
        return new CoarseOutcome.Rejected(CreditsResult.notFound(reason));
    }

    /** Whole seconds under two minutes, otherwise rounded minutes. */
    private static String duration(long ms) {
        long seconds = ms / 1000;
        return seconds < 120 ? seconds + " s" : Math.round(seconds / 60.0) + " min";
    }

    private static String clock(long ms) {
        long totalSeconds = ms / 1000;
        return String.format("%d:%02d:%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60);
    }
}
