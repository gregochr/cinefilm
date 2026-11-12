package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;

/**
 * Whether a forced subtitle file is "proven" (owner decision, 2026-11-12: trust only proven {@code .srt} files): the
 * owner said it looks right, or the comparison with OpenSubtitles agrees (phase 2d), or its timing is aligned to the
 * film's own English track (phase 3a). A file that is none of these and not owner-verified is a candidate for
 * replacement by a checked download (phase 4). Nothing uses this to change playback yet. Pure.
 */
public final class SidecarTrust {

    private SidecarTrust() {
    }

    /**
     * True when any one proof holds.
     *
     * @param ownerVerified true while the owner's check counts (see {@link SubtitleSidecar#isVerifiedFor})
     * @param check the file's comparison with OpenSubtitles, or null when it has none <em>or it is stale</em> (a
     *     stale check proves nothing)
     * @param alignment the file's fit to the film's own English track, or null when it has none or it is stale
     */
    public static boolean isProven(boolean ownerVerified, CheckDto check, AlignmentDto alignment) {
        return ownerVerified
            || check != null && "AGREES".equals(check.verdict())
            || alignment != null && TimingFit.Verdict.ALIGNED.name().equals(alignment.verdict());
    }

    /**
     * True when any one proof holds for a file as Admin lists it (its check and alignment are present only while
     * current).
     */
    public static boolean isProven(SidecarDto sidecar) {
        return isProven(sidecar.verified(), sidecar.check(), sidecar.alignment());
    }
}
