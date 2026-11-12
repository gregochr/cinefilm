package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import gregochr.cinefilm.subtitle.SubtitleComparison.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The decisions of walking an edition's OpenSubtitles candidates: when to move on to the next one, and which result to
 * keep. Pure: comparisons in, decisions out.
 *
 * <p>The walk stops at the first {@link Verdict#AGREES}. A {@link Verdict#CHECK} makes it move on to the next
 * candidate when the reference was full subtitles (it could not be judged for completeness) or when the result looks
 * like another cut of the film: the best offset is more than {@value #OTHER_CUT_OFFSET_MS} ms either way, or fewer than
 * {@value #OTHER_CUT_PERCENT}% of the served lines line up while both files have at least {@value #OTHER_CUT_MIN_LINES}
 * lines. A served file that is itself full subtitles (at least {@value SubtitleComparison#FULL_LINES} lines and more
 * than twice the reference) never makes it move on: another candidate would not change that.
 */
public final class ReferenceWalk {

    /** A best offset further than this, either way, looks like another cut (exclusive). */
    static final long OTHER_CUT_OFFSET_MS = 30_000;
    /** Fewer than this share of served lines (in percent) lining up looks like another cut. */
    static final long OTHER_CUT_PERCENT = 30;
    /** The share test applies only when both files have at least this many lines (inclusive). */
    static final int OTHER_CUT_MIN_LINES = 8;
    /** The most candidates an edition is ever compared with, per IMDb id. */
    public static final int MAX_CANDIDATES = 3;

    private ReferenceWalk() {
    }

    /**
     * One candidate that was compared.
     *
     * @param reference the reference compared with
     * @param result the comparison
     */
    public record Attempt(OpenSubtitlesReference reference, Result result) {
    }

    /**
     * Whether a comparison looks like it was against another cut or release of the film. Only a
     * {@link Verdict#CHECK} can; a served file that is itself full subtitles is not another cut.
     */
    static boolean looksLikeOtherCut(Result r) {
        if (r.verdict() != Verdict.CHECK || servedIsFull(r)) {
            return false;
        }
        boolean farOff = Math.abs((long) r.bestOffsetMs()) > OTHER_CUT_OFFSET_MS;
        boolean fewLineUp = r.servedCount() >= OTHER_CUT_MIN_LINES && r.referenceCount() >= OTHER_CUT_MIN_LINES
            && r.matched() * 100L < r.servedCount() * OTHER_CUT_PERCENT;
        return farOff || fewLineUp;
    }

    private static boolean servedIsFull(Result r) {
        return !r.fullReference() && r.servedCount() >= SubtitleComparison.FULL_LINES
            && r.servedCount() > r.referenceCount() * 2L;
    }

    /** True when the walk should try the next candidate after this comparison. */
    public static boolean moveOn(Result r) {
        if (r.verdict() == Verdict.AGREES) {
            return false;
        }
        return r.fullReference() || looksLikeOtherCut(r);
    }

    /**
     * The attempt to keep: the first {@link Verdict#AGREES}; otherwise the most plausible {@link Verdict#CHECK}: a
     * reference that was not full subtitles with the most served lines lining up, then full references by the same
     * measure; the earlier attempt wins a tie. (All attempts compare the same served cues, so the count of lines lining
     * up orders them as the share would, without division.)
     *
     * @param attempts the comparisons made, in walk order; not empty
     * @return the one to keep
     */
    public static Attempt choose(List<Attempt> attempts) {
        for (Attempt attempt : attempts) {
            if (attempt.result().verdict() == Verdict.AGREES) {
                return attempt;
            }
        }
        List<Attempt> ordered = new ArrayList<>(attempts);
        ordered.sort(Comparator.comparing((Attempt a) -> a.result().fullReference())
            .thenComparing(a -> a.result().matched(), Comparator.reverseOrder()));
        return ordered.get(0);
    }

    /**
     * The reasons to store for the kept attempt: its own, led by what was tried when more than one reference was
     * compared and none agreed.
     *
     * @param attempts the comparisons made, in walk order; not empty
     * @param kept the one kept ({@link #choose})
     * @return the reasons
     */
    public static List<String> reasons(List<Attempt> attempts, Attempt kept) {
        List<String> reasons = new ArrayList<>();
        if (attempts.size() > 1 && kept.result().verdict() == Verdict.CHECK) {
            boolean allOtherCut = attempts.stream().allMatch(a -> moveOn(a.result()));
            reasons.add("Tried " + attempts.size() + " OpenSubtitles files; "
                + (allOtherCut ? "they look like a different cut" : "none of them agreed with this file"));
        }
        reasons.addAll(kept.result().reasons());
        return reasons;
    }
}
