package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.util.List;

/**
 * The rules of phase 3c-1 (forced-timing-reference-plan.md): which editions get a full English reference to measure
 * their forced files against, which OpenSubtitles files may be that reference, and when a stored one is usable. The
 * numbers are starting values; the measurements' log lines are what revisits them. Pure.
 */
final class TimingReferenceRule {

    /** A forced file with fewer cues than this is {@code TOO_FEW} to fit anyway. */
    static final int MIN_FORCED_CUES = TimingFit.MIN_CUES;
    /** A reference needs this many lines (and a forced file with this many or more is full subtitles). */
    static final int FULL_LINES = SubtitleComparison.FULL_LINES;
    /** A reference's last cue must end before the runtime plus this. */
    static final long END_SLACK_MS = 60_000;
    /** With credits detected, a reference's last cue must end after the credits start minus this. */
    static final long CREDITS_SLACK_MS = 10 * 60_000L;
    /** A reference matching fewer than this share of a forced file's lines is passed over for the next candidate. */
    static final double MIN_SHARE = 0.30;
    /** Reference files kept per film and IMDb id for the timing check. */
    static final int MAX_REFERENCES = 3;
    /** Reference files fetched in one check run. */
    static final int MAX_FETCHES_PER_RUN = 2;
    /** The frame rate of the library's films, and how far from it a file's stated rate may be. */
    static final double TARGET_FPS = 23.976;
    static final double FPS_TOLERANCE = 0.01;

    private TimingReferenceRule() {
    }

    /**
     * One forced file of an edition, as the timing check covers it.
     *
     * @param lines its cue count
     * @param verified true when the owner verified it
     * @param wholeEdition true when it is timed for the whole edition
     */
    record FileFacts(int lines, boolean verified, boolean wholeEdition) {
    }

    /**
     * Whether an edition's forced files get a timing reference. All must hold: it has covered files, one of them has
     * at least {@value #MIN_FORCED_CUES} and fewer than {@value #FULL_LINES} cues, none is owner-verified, the
     * edition does not play its embedded forced track, the runtime is known, and it is a single part or every file is
     * timed for the whole edition. (The caller has already required the embedded read to be {@code NO_REFERENCE} for
     * every part.)
     *
     * @param files the edition's forced files the timing check covers
     * @param playsEmbedded true when the edition plays its embedded forced track
     * @param runtimeMs the edition's runtime, 0 when a part's length is unknown
     * @param partCount how many original parts the edition has
     * @return true when the edition qualifies
     */
    static boolean qualifies(List<FileFacts> files, boolean playsEmbedded, long runtimeMs, int partCount) {
        return !files.isEmpty()
            && files.stream().anyMatch(f -> f.lines() >= MIN_FORCED_CUES && f.lines() < FULL_LINES)
            && files.stream().noneMatch(FileFacts::verified)
            && !playsEmbedded
            && runtimeMs > 0
            && (partCount == 1 || files.stream().allMatch(FileFacts::wholeEdition));
    }

    /** True when the file's stated frame rate is unknown or about 23.976 (the library's rate). */
    static boolean plausibleFps(OpenSubtitlesResult candidate) {
        return candidate.fps() == null || Math.abs(candidate.fps() - TARGET_FPS) <= FPS_TOLERANCE;
    }

    /**
     * Whether a stored reference can stand for the film: 300 lines or more, a last cue ending before the runtime plus
     * 60 s, and, when credits detection found the credits, a last cue ending after the credits start less 10 minutes
     * (so it is neither a short cut nor another film's longer file).
     *
     * @param cueCount the reference's cue count
     * @param lastCueEndMs where its last cue ends
     * @param runtimeMs the edition's runtime
     * @param creditsStartMs where credits detection put the credits start on the edition's timeline, or null
     * @return true when usable
     */
    static boolean usable(int cueCount, long lastCueEndMs, long runtimeMs, Long creditsStartMs) {
        return cueCount >= FULL_LINES && lastCueEndMs < runtimeMs + END_SLACK_MS
            && (creditsStartMs == null || lastCueEndMs > creditsStartMs - CREDITS_SLACK_MS);
    }

    /** {@link #usable(int, long, long, Long)} for a stored reference (last cue end from its text if not kept). */
    static boolean usable(OpenSubtitlesReference reference, long runtimeMs, Long creditsStartMs) {
        return usable(reference.getCueCount(), lastCueEnd(reference), runtimeMs, creditsStartMs);
    }

    /** Where a reference's last cue ends: the figure kept when it was fetched, else worked out from its text. */
    static long lastCueEnd(OpenSubtitlesReference reference) {
        if (reference.getLastCueEndMs() != null) {
            return reference.getLastCueEndMs();
        }
        return SrtParser.cues(reference.getCuesText()).stream().mapToLong(SubtitleCue::endMs).max().orElse(0);
    }
}
