package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What to download for an edition, and from which OpenSubtitles file. Pure: facts in, decisions out.
 *
 * <p>An edition is due for a download when the film's need is shown and one of these holds:
 * <ul>
 *   <li>{@link Kind#MISSING}: nothing plays from the owner's files, a download or the embedded track, and nothing is
 *       waiting to be proven (no download that has not failed);</li>
 *   <li>{@link Kind#REPLACEMENT}: the owner's file failed every check and nothing has replaced it yet (no download
 *       that has not failed, no embedded track).</li>
 * </ul>
 * Two files in conflict are the owner's to sort out: nothing is downloaded for that edition. At most
 * {@value #MAX_DOWNLOADS} files are ever made into downloads (or promoted) per edition and IMDb id, counting the ones
 * the owner removed.
 */
public final class DownloadPlan {

    /** The most OpenSubtitles files ever made into downloads for an edition and IMDb id, removed ones included. */
    public static final int MAX_DOWNLOADS = 3;

    private DownloadPlan() {
    }

    /** Why an edition is due. */
    public enum Kind {
        /** No subtitle plays at all: the highest priority for the day's budget. */
        MISSING,
        /** The owner's file failed every check: a lower priority than a missing one. */
        REPLACEMENT
    }

    /**
     * Whether an edition is due, and why.
     *
     * @param needed true when the film's need for forced subtitles is shown
     * @param fileConflict true when two of the owner's files compete for the same place
     * @param file the owner's file(s) that would play, or null when there is none
     * @param hasLiveDownload true when the edition has a download that has not failed
     * @param embedded true when the edition has an embedded forced text track
     * @return the kind, or empty when nothing is due
     */
    static Optional<Kind> kind(boolean needed, boolean fileConflict, FileSide file, boolean hasLiveDownload,
                               boolean embedded) {
        if (!needed || fileConflict || hasLiveDownload || embedded) {
            return Optional.empty();
        }
        if (file == null) {
            return Optional.of(Kind.MISSING);
        }
        return file.assessment().trust() == Trust.FAILED ? Optional.of(Kind.REPLACEMENT) : Optional.empty();
    }

    /**
     * Whether a reference is good enough to become a download: it has lines, and fewer than
     * {@value SubtitleComparison#FULL_LINES} (a file of that many is full subtitles, whatever OpenSubtitles' flag
     * says).
     */
    static boolean promotable(OpenSubtitlesReference reference) {
        return reference.isUsable() && reference.getCueCount() < SubtitleComparison.FULL_LINES;
    }

    /**
     * The stored reference to promote for a replacement (it costs no download): among the references this edition has
     * already been compared with (its ledger), single-file candidates that are promotable, not blocked and not already
     * downloads, whose cut fits the owner's file: the comparison with it does not look like another cut
     * ({@link ReferenceWalk#looksLikeOtherCut}, the walk's own test). The reference the walk kept for the owner's file
     * comes first, then the film's candidates in the order for this edition's cut.
     *
     * @param ranked the film's single-file candidates in the order for this edition's cut
     * @param keptFileId the file id of the reference the walk kept for the owner's file, or null
     * @param stored the film's stored references by file id (for this IMDb id)
     * @param ledger the file ids this edition has been compared with
     * @param skipped the file ids that are blocked or already downloads for this edition
     * @param fileCues the owner's file's cues on the edition's timeline
     * @return the reference to promote, or empty
     */
    static Optional<OpenSubtitlesReference> promotion(List<OpenSubtitlesResult> ranked, Long keptFileId,
                                                       Map<Long, OpenSubtitlesReference> stored, Set<Long> ledger,
                                                       Set<Long> skipped, List<SubtitleCue> fileCues) {
        List<Long> order = new ArrayList<>();
        if (keptFileId != null) {
            order.add(keptFileId);
        }
        ranked.forEach(c -> {
            if (!order.contains(c.fileId())) {
                order.add(c.fileId());
            }
        });
        Set<Long> single = new HashSet<>();
        ranked.forEach(c -> single.add(c.fileId()));
        for (Long fileId : order) {
            OpenSubtitlesReference reference = stored.get(fileId);
            if (reference == null || !single.contains(fileId) || !ledger.contains(fileId) || skipped.contains(fileId)
                || !promotable(reference)) {
                continue;
            }
            Result result = SubtitleComparison.compare(fileCues, SrtParser.cues(reference.getCuesText()));
            if (!ReferenceWalk.looksLikeOtherCut(result)) {
                return Optional.of(reference);
            }
        }
        return Optional.empty();
    }

    /**
     * The files to try downloading, in order: the film's candidates for this edition's cut, leaving out those that are
     * blocked or already downloads, that this edition has already been compared with (they were judged: full, another
     * cut, or promotable above), and those already stored and not usable as a download (unreadable, or full
     * subtitles).
     *
     * @param ranked the film's single-file candidates in the order for this edition's cut
     * @param skipped the file ids that are blocked or already downloads for this edition
     * @param ledger the file ids this edition has been compared with
     * @param stored the film's stored references by file id (for this IMDb id)
     * @return the candidates to fetch, best first
     */
    static List<OpenSubtitlesResult> toFetch(List<OpenSubtitlesResult> ranked, Set<Long> skipped, Set<Long> ledger,
                                             Map<Long, OpenSubtitlesReference> stored) {
        List<OpenSubtitlesResult> out = new ArrayList<>();
        for (OpenSubtitlesResult candidate : ranked) {
            OpenSubtitlesReference reference = stored.get(candidate.fileId());
            boolean spent = reference != null && reference.isFinal() && !promotable(reference);
            if (!skipped.contains(candidate.fileId()) && !ledger.contains(candidate.fileId()) && !spent) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** How many more fetches an edition may make, given the downloads and blocked files it already has. */
    static int remaining(Collection<?> existingDownloadsAndBlocks) {
        return Math.max(0, MAX_DOWNLOADS - existingDownloadsAndBlocks.size());
    }
}
