package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingFit.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fitting a forced file to full English files from OpenSubtitles (phase 3c-2a, forced-timing-reference-plan.md), for
 * films with no English track of their own. Two files agreeing says the lines match another release, not that the
 * timing fits this copy of the film, so these fits are shown and never count as proof (see {@link SidecarTrust}) and
 * never set an offset. The thresholds are provisional until 3c-1's measurement lines are read. Pure.
 */
public final class DownloadedFit {

    /** The largest offset an {@code ALIGNED} fit may have against a downloaded file, in ms (provisional). */
    static final int MAX_ALIGNED_OFFSET_MS = 1_000;
    /**
     * The version of {@link #judge}'s rules, part of the stored reference key. Bump it when the rules change, so
     * stored OpenSubtitles fits are re-judged. 2 since 2026-11-20.
     */
    public static final int RULES_VERSION = 2;
    /** A served file needs at least this many lines to be full subtitles rather than forced ones. */
    static final int FULL_LINES = SubtitleComparison.FULL_LINES;
    /**
     * A reference under this many lines may itself be a foreign-parts file mislabelled as full (The Equalizer 3: 348
     * lines), so against it only decision 38's "more than twice" counts; against a larger reference, a file of
     * {@value #FULL_LINES} lines or more with more than half its lines is full subtitles.
     */
    static final int FORCED_SIZED_REFERENCE_LINES = 2 * FULL_LINES;

    private DownloadedFit() {
    }

    /**
     * A stored full English file the forced file may be fitted to.
     *
     * @param osFileId its OpenSubtitles file id
     * @param release its release name, or null
     * @param cueCount its line count
     * @param starts its cue starts in milliseconds
     * @param servedStarts the served file's cue starts to fit to it instead of the ones given to {@link #choose}, when
     *     the rate rule compares the served file through its original cues; null otherwise
     */
    public record Reference(long osFileId, String release, int cueCount, List<Long> starts,
                            List<Long> servedStarts) {

        /** A file fitted with the served cues as they are stored. */
        public Reference(long osFileId, String release, int cueCount, List<Long> starts) {
            this(osFileId, release, cueCount, starts, null);
        }

        /** The file as the reasons name it. */
        String label() {
            return release != null ? release : "file " + osFileId;
        }
    }

    /**
     * The best fit.
     *
     * @param reference the file it is against
     * @param result the verdict (see {@link #judge})
     */
    public record Choice(Reference reference, Result result) {
    }

    /**
     * Turns a raw fit against a downloaded file into a verdict. A file of {@value #FULL_LINES} lines or more with more
     * than half the reference's lines is full subtitles and never {@code ALIGNED}, except against a reference of under
     * {@value #FORCED_SIZED_REFERENCE_LINES} lines, where it needs more than twice the reference's (decision 38).
     * {@link TimingFit}'s {@code ALIGNED} within {@value #MAX_ALIGNED_OFFSET_MS} ms is {@code ALIGNED};
     * the same match further out is {@code CHECK} (one of the two is timed for another
     * release). A {@code CHECK} whose only reason is a loose median (at least 80% matched,
     * offset within {@link TimingFit#MAX_ALIGNED_OFFSET_MS}) says the lines match but only loosely;
     * anything else is {@code CHECK}. Too few lines stays {@code TOO_FEW}.
     *
     * @param fit the raw fit of the served cue starts to the reference's
     * @param reference the file fitted to
     * @return the verdict with reasons that name the file and do not blame the forced one
     */
    static Result judge(Result fit, Reference reference) {
        if (fit.verdict() == Verdict.TOO_FEW || fit.verdict() == Verdict.NO_REFERENCE) {
            return fit;
        }
        if (looksFull(fit.servedCount(), reference.cueCount())) {
            return withVerdict(fit, Verdict.CHECK, "Looks like full subtitles, not forced");
        }
        if (fit.verdict() == Verdict.ALIGNED) {
            if (Math.abs(fit.fittedOffsetMs()) <= MAX_ALIGNED_OFFSET_MS) {
                return withVerdict(fit, Verdict.ALIGNED,
                    "Lines match a full English file from OpenSubtitles (" + reference.label() + ")");
            }
            return withVerdict(fit, Verdict.CHECK, String.format(Locale.ROOT, "Lines match a full English file from "
                + "OpenSubtitles (%s) but %.1f s apart: one of them is timed for another release", reference.label(),
                Math.abs(fit.fittedOffsetMs()) / 1000.0));
        }
        if (onlyLooselyMatched(fit)) {
            return withVerdict(fit, Verdict.CHECK, "Lines match a full English file from OpenSubtitles ("
                + reference.label() + ") but only loosely (typically " + fit.medianResidualMs()
                + " ms off): one of them may be timed slightly differently");
        }
        return withVerdict(fit, Verdict.CHECK, "The lines do not line up well enough with the full English file from "
            + "OpenSubtitles (" + reference.label() + ") to tell: it may be another cut or timed differently");
    }

    private static boolean looksFull(int served, int referenceCount) {
        return served >= FULL_LINES && served * 2L > referenceCount
            && (referenceCount >= FORCED_SIZED_REFERENCE_LINES || served > referenceCount * 2L);
    }

    /** A CHECK whose single reason is the loose median: nearly every line matches, within the offset limit. */
    private static boolean onlyLooselyMatched(Result fit) {
        return fit.verdict() == Verdict.CHECK && fit.reasons().size() == 1
            && TimingFit.enoughMatched(fit.matchedCount(), fit.servedCount())
            && Math.abs(fit.fittedOffsetMs()) <= TimingFit.MAX_ALIGNED_OFFSET_MS
            && fit.medianResidualMs() > TimingFit.MAX_MEDIAN_RESIDUAL_MS;
    }

    private static Result withVerdict(Result fit, Verdict verdict, String reason) {
        return new Result(verdict, fit.fittedOffsetMs(), fit.matchedShare(), fit.matchedCount(), fit.servedCount(),
            fit.medianResidualMs(), List.of(reason));
    }

    /** Best first: {@code ALIGNED}, then the larger matched share, then the smaller median residual, then file id. */
    private static final Comparator<Choice> BEST_FIRST = Comparator
        .comparing((Choice c) -> c.result().verdict() != Verdict.ALIGNED)
        .thenComparing((a, b) -> Long.compare((long) b.result().matchedCount() * a.result().servedCount(),
            (long) a.result().matchedCount() * b.result().servedCount()))
        .thenComparingInt(c -> c.result().medianResidualMs())
        .thenComparingLong(c -> c.reference().osFileId());

    /**
     * Fits the served cue starts to each reference and keeps the best fit: an {@code ALIGNED} one first, then the
     * larger matched share (exact cross-multiplication), then the smaller median residual, then the lower file id.
     *
     * @param served the forced file's cue starts
     * @param references the usable stored files (at least one)
     * @return the best fit
     */
    public static Choice choose(List<Long> served, List<Reference> references) {
        List<Choice> fits = new ArrayList<>();
        for (Reference reference : references) {
            List<Long> from = reference.servedStarts() != null ? reference.servedStarts() : served;
            fits.add(new Choice(reference, judge(TimingFit.fit(from, reference.starts()), reference)));
        }
        return fits.stream().min(BEST_FIRST).orElseThrow();
    }

    /** Compares two choices by the order of {@link #choose} (negative when {@code a} is better). Visible to tests. */
    static int compare(Choice a, Choice b) {
        return BEST_FIRST.compare(a, b);
    }
}
