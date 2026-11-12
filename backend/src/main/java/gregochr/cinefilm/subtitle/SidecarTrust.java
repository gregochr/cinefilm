package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;

/**
 * Whether a forced subtitle is "proven" (owner decision, 2026-11-12: trust only proven subtitles): the owner said it
 * looks right, or the comparison with OpenSubtitles agrees (phase 2d), or its timing is aligned to the film's own
 * English track (phase 3a). One that is none of these and has a current {@code CHECK} verdict has failed; one that is
 * none of these with no such verdict is unchecked (nothing says it is wrong, or it could not be checked). Playback uses
 * this to choose between an owner's file, a download and the film's own track ({@link ForcedChoice}). Pure.
 */
public final class SidecarTrust {

    private SidecarTrust() {
    }

    /** How far a subtitle can be trusted. */
    public enum Trust {
        /** The owner verified it, or an automatic check agrees. */
        PROVEN,
        /** Nothing proves it and nothing says it is wrong: no current comparison, or the checks could not be made. */
        UNCHECKED,
        /** Not proven, and the comparison with OpenSubtitles says {@code CHECK}. */
        FAILED
    }

    /** What proves a subtitle (the first that holds, in this order). */
    public enum Proof {
        /** Nothing does. */
        NONE,
        /** The owner said it looks right. */
        VERIFIED,
        /** The comparison with OpenSubtitles agrees. */
        AGREES,
        /** Its timing lines up with the film's own English track. */
        ALIGNED
    }

    /**
     * Where a subtitle stands.
     *
     * @param trust proven, unchecked or failed
     * @param proof what proves it ({@link Proof#NONE} unless {@code trust} is {@link Trust#PROVEN})
     */
    public record Assessment(Trust trust, Proof proof) {
    }

    /**
     * Where a subtitle stands.
     *
     * @param ownerVerified true while the owner's check counts (see {@link SubtitleSidecar#isVerifiedFor})
     * @param checkVerdict the comparison with OpenSubtitles ({@code AGREES} or {@code CHECK}), or null when there is
     *     none <em>or it is stale</em> (a stale check proves nothing)
     * @param aligned true when the timing fit is current and {@code ALIGNED} (for every file a subtitle is made of)
     * @return the assessment: owner verification beats an automatic proof (and any failure), then {@code AGREES},
     *     then {@code ALIGNED}; a {@code CHECK} that nothing proves is a failure
     */
    public static Assessment assess(boolean ownerVerified, String checkVerdict, boolean aligned) {
        if (ownerVerified) {
            return new Assessment(Trust.PROVEN, Proof.VERIFIED);
        }
        if ("AGREES".equals(checkVerdict)) {
            return new Assessment(Trust.PROVEN, Proof.AGREES);
        }
        if (aligned) {
            return new Assessment(Trust.PROVEN, Proof.ALIGNED);
        }
        return new Assessment("CHECK".equals(checkVerdict) ? Trust.FAILED : Trust.UNCHECKED, Proof.NONE);
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
        return assess(ownerVerified, check == null ? null : check.verdict(), isAligned(alignment))
            .trust() == Trust.PROVEN;
    }

    /**
     * True when any one proof holds for a file as Admin lists it (its check and alignment are present only while
     * current).
     */
    public static boolean isProven(SidecarDto sidecar) {
        return isProven(sidecar.verified(), sidecar.check(), sidecar.alignment());
    }

    /** True when the alignment is present (current) and {@code ALIGNED}. */
    static boolean isAligned(AlignmentDto alignment) {
        return alignment != null && TimingFit.Verdict.ALIGNED.name().equals(alignment.verdict());
    }
}
