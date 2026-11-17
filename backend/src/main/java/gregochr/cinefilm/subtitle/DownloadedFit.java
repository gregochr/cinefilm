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
    /** A served file with this many lines or more is full subtitles, not forced ones. */
    static final int FULL_LINES = SubtitleComparison.FULL_LINES;

    private DownloadedFit() {
    }

    /**
     * A stored full English file the forced file may be fitted to.
     *
     * @param osFileId its OpenSubtitles file id
     * @param release its release name, or null
     * @param cueCount its line count
     * @param starts its cue starts in milliseconds
     */
    public record Reference(long osFileId, String release, int cueCount, List<Long> starts) {

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
     * Turns a raw fit against a downloaded file into a verdict. A file of 300 lines or more, or with more than half
     * the reference's lines, is full subtitles and never {@code ALIGNED}. {@link TimingFit}'s {@code ALIGNED} within
     * {@value #MAX_ALIGNED_OFFSET_MS} ms is {@code ALIGNED}; the same match further out is {@code CHECK} (one of the
     * two is timed for another release); anything else is {@code CHECK}. Too few lines stays {@code TOO_FEW}.
     *
     * @param fit the raw fit of the served cue starts to the reference's
     * @param reference the file fitted to
     * @return the verdict with reasons that name the file and do not blame the forced one
     */
    static Result judge(Result fit, Reference reference) {
        if (fit.verdict() == Verdict.TOO_FEW || fit.verdict() == Verdict.NO_REFERENCE) {
            return fit;
        }
        if (fit.servedCount() >= FULL_LINES || fit.servedCount() * 2 > reference.cueCount()) {
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
        return withVerdict(fit, Verdict.CHECK, "The lines do not line up well enough with the full English file from "
            + "OpenSubtitles (" + reference.label() + ") to tell: it may be another cut or timed differently");
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
            fits.add(new Choice(reference, judge(TimingFit.fit(served, reference.starts()), reference)));
        }
        return fits.stream().min(BEST_FIRST).orElseThrow();
    }

    /** Compares two choices by the order of {@link #choose} (negative when {@code a} is better). Visible to tests. */
    static int compare(Choice a, Choice b) {
        return BEST_FIRST.compare(a, b);
    }
}
