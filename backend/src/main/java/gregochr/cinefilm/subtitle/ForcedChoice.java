package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SidecarTrust.Proof;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which forced subtitle an edition plays (owner decision 2026-11-12: trust only proven files). Pure: what is known
 * about the edition's owner file, its downloads and its embedded track in, a decision and the reason for every
 * subtitle out. In order:
 * <ol>
 *   <li>a proven owner file;</li>
 *   <li>a proven download (the lowest id when several are);</li>
 *   <li>an embedded forced text track (it ships with the rip, so it counts as proven);</li>
 *   <li>an owner file that is not proven but has not failed (unchecked);</li>
 *   <li>an owner file that failed is replaced by a download that has not failed (unchecked, as a proven one won at 2);
 *       with no such download it keeps playing, unless it has {@value SubtitleComparison#FULL_LINES} lines or more
 *       (it is then full subtitles, which are worse than none: it is dropped);</li>
 *   <li>an unchecked download, when there is no owner file.</li>
 * </ol>
 * A download that failed never plays. An edition with nothing from this list shows no forced subtitle.
 */
public final class ForcedChoice {

    /** A served owner file of this many lines or more that failed is full subtitles, not forced ones. */
    public static final int FULL_LINES = SubtitleComparison.FULL_LINES;

    private ForcedChoice() {
    }

    /** Where the chosen subtitle comes from. */
    public enum Source {
        /** The owner's file(s) beside the film. */
        FILE,
        /** A download from OpenSubtitles. */
        DOWNLOAD,
        /** The film's own embedded forced text track (the player picks it; no subtitle is served from here). */
        EMBEDDED,
        /** Nothing plays. */
        NONE
    }

    /** Which row of the order above applied. */
    public enum Step {
        /** 1: a proven owner file. */
        PROVEN_FILE,
        /** 2: a proven download. */
        PROVEN_DOWNLOAD,
        /** 3: the embedded forced text track. */
        EMBEDDED,
        /** 4: an owner file nothing says is wrong. */
        UNCHECKED_FILE,
        /** 5: a failed owner file replaced by an unchecked download. */
        REPLACED_BY_DOWNLOAD,
        /** 5: a failed owner file with nothing better, kept. */
        FAILED_FILE_KEPT,
        /** 5: a failed owner file of 300 lines or more, dropped with no replacement. */
        FAILED_FILE_DROPPED,
        /** 6: an unchecked download, no owner file. */
        UNCHECKED_DOWNLOAD,
        /** Nothing plays. */
        NONE
    }

    /**
     * The edition's owner file(s) as playback would serve them (the files' own pick).
     *
     * @param assessment where it stands
     * @param lines how many cues it has in all
     */
    public record FileSide(Assessment assessment, int lines) {
    }

    /**
     * One download of the edition.
     *
     * @param id the download's row id
     * @param assessment where it stands
     */
    public record DownloadSide(Long id, Assessment assessment) {
    }

    /**
     * What is known about an edition.
     *
     * @param file the owner's file(s) to play, or null when there is none (two files in conflict are none)
     * @param downloads the edition's downloads (any state)
     * @param embedded true when a file of the edition has an English (or unlabelled) forced text track
     */
    public record Input(FileSide file, List<DownloadSide> downloads, boolean embedded) {
    }

    /**
     * The decision.
     *
     * @param step which row of the order applied
     * @param source where the playing subtitle comes from
     * @param downloadId the playing download's id when {@code source} is {@link Source#DOWNLOAD}, else null
     * @param fileReason why the owner's file plays or not, or null when there is no file
     * @param downloadReasons why each download plays or not, by id
     * @param hasLiveDownload true when the edition has a download that has not failed (it plays, or is waiting to
     *     be proven): nothing more needs downloading
     * @param fileReplaced true when a download plays in place of the owner's file because the file failed its checks
     *     (the owner can answer "Looks right")
     */
    public record Decision(Step step, Source source, Long downloadId, String fileReason,
                           Map<Long, String> downloadReasons, boolean hasLiveDownload, boolean fileReplaced) {
    }

    /**
     * Chooses.
     *
     * @param input what is known
     * @return the decision
     */
    public static Decision decide(Input input) {
        FileSide file = input.file();
        List<DownloadSide> live = input.downloads().stream()
            .filter(d -> d.assessment().trust() != Trust.FAILED).toList();
        DownloadSide proven = first(live, Trust.PROVEN);
        DownloadSide unchecked = first(live, Trust.UNCHECKED);
        Trust fileTrust = file == null ? null : file.assessment().trust();
        Step step;
        DownloadSide playing = null;
        if (fileTrust == Trust.PROVEN) {
            step = Step.PROVEN_FILE;
        } else if (proven != null) {
            step = Step.PROVEN_DOWNLOAD;
            playing = proven;
        } else if (input.embedded()) {
            step = Step.EMBEDDED;
        } else if (fileTrust == Trust.UNCHECKED) {
            step = Step.UNCHECKED_FILE;
        } else if (fileTrust == Trust.FAILED) {
            if (unchecked != null) {
                step = Step.REPLACED_BY_DOWNLOAD;
                playing = unchecked;
            } else if (file.lines() >= FULL_LINES) {
                step = Step.FAILED_FILE_DROPPED;
            } else {
                step = Step.FAILED_FILE_KEPT;
            }
        } else if (unchecked != null) {
            step = Step.UNCHECKED_DOWNLOAD;
            playing = unchecked;
        } else {
            step = Step.NONE;
        }
        Source source = switch (step) {
            case PROVEN_FILE, UNCHECKED_FILE, FAILED_FILE_KEPT -> Source.FILE;
            case PROVEN_DOWNLOAD, REPLACED_BY_DOWNLOAD, UNCHECKED_DOWNLOAD -> Source.DOWNLOAD;
            case EMBEDDED -> Source.EMBEDDED;
            case FAILED_FILE_DROPPED, NONE -> Source.NONE;
        };
        Long playingId = playing == null ? null : playing.id();
        Map<Long, String> reasons = new HashMap<>();
        for (DownloadSide d : input.downloads()) {
            reasons.put(d.id(), downloadReason(d, step, playingId));
        }
        return new Decision(step, source, playingId, file == null ? null : fileReason(file, step, playing),
            Map.copyOf(reasons), !live.isEmpty(), fileTrust == Trust.FAILED && source == Source.DOWNLOAD);
    }

    /** The best download of a trust level: the lowest id. */
    private static DownloadSide first(List<DownloadSide> downloads, Trust trust) {
        return downloads.stream().filter(d -> d.assessment().trust() == trust)
            .min(Comparator.comparing(DownloadSide::id)).orElse(null);
    }

    private static String proofText(Proof proof) {
        return proof == Proof.VERIFIED ? "you checked it"
            : proof == Proof.AGREES ? "it agrees with OpenSubtitles"
            : "its timing lines up with the film's own English track";
    }

    /** Why the owner's file plays or not (only asked when there is a file, so the last arm is the unchecked file). */
    private static String fileReason(FileSide file, Step step, DownloadSide playing) {
        return switch (step) {
            case PROVEN_FILE -> "Plays: " + proofText(file.assessment().proof());
            case PROVEN_DOWNLOAD -> file.assessment().trust() == Trust.FAILED
                ? "Replaced by an OpenSubtitles download that " + downloadProof(playing.assessment().proof())
                : "Not used: a proven OpenSubtitles download plays instead";
            case EMBEDDED -> "Not used: the film's own forced subtitle track plays instead";
            case REPLACED_BY_DOWNLOAD ->
                "Replaced by an OpenSubtitles download that has not been checked yet; this file failed its checks";
            case FAILED_FILE_KEPT ->
                "Plays only because nothing better exists: it failed its checks (needs checking)";
            case FAILED_FILE_DROPPED -> "Not used: it failed its checks and looks like full subtitles ("
                + file.lines() + " lines), not forced ones";
            default -> "Plays: nothing says it is wrong, but it is not proven yet";
        };
    }

    private static String downloadProof(Proof proof) {
        return proof == Proof.VERIFIED ? "you checked"
            : proof == Proof.AGREES ? "agrees"
            : "is in time with the film's own English track";
    }

    private static String downloadReason(DownloadSide d, Step step, Long playingId) {
        Trust trust = d.assessment().trust();
        if (trust == Trust.FAILED) {
            return "Not used: it failed its checks";
        }
        if (d.id().equals(playingId)) {
            return switch (step) {
                case PROVEN_DOWNLOAD -> "Plays: " + proofText(d.assessment().proof());
                case REPLACED_BY_DOWNLOAD ->
                    "Plays instead of the owner's file, which failed its checks; not checked yet";
                default -> "Plays: nothing else is available; not checked yet";
            };
        }
        if (playingId != null) {
            return "Not used: another OpenSubtitles download plays";
        }
        String why = switch (step) {
            case PROVEN_FILE -> "your file is proven and plays first";
            case EMBEDDED -> "the film's own forced subtitle track plays instead";
            default -> "your file plays first: nothing says it is wrong";
        };
        return "Not used: " + why;
    }
}
