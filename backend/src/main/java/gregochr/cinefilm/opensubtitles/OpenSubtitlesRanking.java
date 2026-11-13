package gregochr.cinefilm.opensubtitles;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which of several subtitles is the best to compare with, in order: a single file (a subtitle split over several
 * discs is only part of the film, so it ranks below every single-file one), frame rate closest to 23.976 (the library's
 * films are all 23.976; one with no frame rate ranks last), from a trusted uploader, not hearing-impaired, most
 * downloaded, then the lowest id so the answer never depends on the order OpenSubtitles listed them in. Pure.
 */
public final class OpenSubtitlesRanking {

    /** The frame rate of every film in the library. */
    static final double TARGET_FPS = 23.976;

    private static final Comparator<OpenSubtitlesResult> ORDER = Comparator
        .comparing(OpenSubtitlesResult::isMultiFile)
        .thenComparingDouble(OpenSubtitlesRanking::fpsDistance)
        .thenComparing(OpenSubtitlesResult::fromTrusted, Comparator.reverseOrder())
        .thenComparing(OpenSubtitlesResult::hearingImpaired)
        .thenComparing(OpenSubtitlesResult::downloadCount, Comparator.reverseOrder())
        .thenComparingLong(OpenSubtitlesResult::id);

    /**
     * The order for full English subtitles: as {@link #ORDER}, but a hearing-impaired file ranks below every other
     * single-file one (it is used only when nothing else exists), whatever its frame rate or downloads.
     */
    private static final Comparator<OpenSubtitlesResult> FULL_ORDER = Comparator
        .comparing(OpenSubtitlesResult::isMultiFile)
        .thenComparing(OpenSubtitlesResult::hearingImpaired)
        .thenComparingDouble(OpenSubtitlesRanking::fpsDistance)
        .thenComparing(OpenSubtitlesResult::fromTrusted, Comparator.reverseOrder())
        .thenComparing(OpenSubtitlesResult::downloadCount, Comparator.reverseOrder())
        .thenComparingLong(OpenSubtitlesResult::id);

    /** The most of the film's ranked single-file results stored as candidates before the per-tag extras. */
    static final int TOP_CANDIDATES = 3;
    /** The most candidates stored in all (the top ones plus the best result of each cut tag). */
    static final int MAX_CANDIDATES = 8;

    private OpenSubtitlesRanking() {
    }

    private static double fpsDistance(OpenSubtitlesResult result) {
        return result.fps() == null ? Double.POSITIVE_INFINITY : Math.abs(result.fps() - TARGET_FPS);
    }

    /**
     * The top-ranked result.
     *
     * @param results the candidates, in any order
     * @return the best, or empty when there are none
     */
    public static Optional<OpenSubtitlesResult> best(List<OpenSubtitlesResult> results) {
        return results.stream().min(ORDER);
    }

    /**
     * Every result in the film-level ranked order.
     *
     * @param results the results, in any order
     * @return a new list, best first
     */
    public static List<OpenSubtitlesResult> rank(List<OpenSubtitlesResult> results) {
        return results.stream().sorted(ORDER).toList();
    }

    /**
     * The results re-ordered for one edition: first by how well each result's release name and file name fit the
     * edition's name ({@link CutTag#affinity}), then by the film-level order. An edition whose name carries no cut
     * tag keeps the film-level order.
     *
     * @param results the results, in any order
     * @param editionName the edition's name, for example {@code Extended Edition}; null reads as no tags
     * @return a new list, best first
     */
    public static List<OpenSubtitlesResult> rankFor(List<OpenSubtitlesResult> results, String editionName) {
        Set<CutTag> wanted = CutTag.in(editionName);
        Comparator<OpenSubtitlesResult> byFit = Comparator
            .comparingInt((OpenSubtitlesResult r) -> CutTag.affinity(wanted, r.cutTags())).reversed();
        return results.stream().sorted(byFit.thenComparing(ORDER)).toList();
    }

    /**
     * The results the check may compare with, in the film-level ranked order: the single-file ones only (a file split
     * over discs is part of a film), the top {@value #TOP_CANDIDATES}, then the best of each cut tag (Extended,
     * Theatrical and so on) not already among them, so that an edition's own cut can be found when it is not
     * the film's overall favourite; at most {@value #MAX_CANDIDATES}.
     *
     * @param results the results, in any order
     * @return the candidates, best first (empty when no result is a single file)
     */
    public static List<OpenSubtitlesResult> candidates(List<OpenSubtitlesResult> results) {
        return candidates(results, ORDER);
    }

    /**
     * The full English results a download may use: as {@link #candidates}, over {@link #FULL_ORDER} (hearing-impaired
     * files last).
     *
     * @param results the results, in any order
     * @return the candidates, best first (empty when no result is a single file)
     */
    public static List<OpenSubtitlesResult> fullCandidates(List<OpenSubtitlesResult> results) {
        return candidates(results, FULL_ORDER);
    }

    /**
     * The full English results re-ordered for one edition: first by how well each fits the edition's cut tags, then by
     * the full order, so among equally fitting files a hearing-impaired one comes last.
     *
     * @param results the results, in any order
     * @param editionName the edition's name; null reads as no tags
     * @return a new list, best first
     */
    public static List<OpenSubtitlesResult> rankForFull(List<OpenSubtitlesResult> results, String editionName) {
        Set<CutTag> wanted = CutTag.in(editionName);
        Comparator<OpenSubtitlesResult> byFit = Comparator
            .comparingInt((OpenSubtitlesResult r) -> CutTag.affinity(wanted, r.cutTags())).reversed();
        return results.stream().sorted(byFit.thenComparing(FULL_ORDER)).toList();
    }

    private static List<OpenSubtitlesResult> candidates(List<OpenSubtitlesResult> results,
                                                        Comparator<OpenSubtitlesResult> order) {
        List<OpenSubtitlesResult> ranked = results.stream().sorted(order)
            .filter(OpenSubtitlesResult::isSingleFile).toList();
        Set<OpenSubtitlesResult> chosen = new LinkedHashSet<>(
            ranked.subList(0, Math.min(TOP_CANDIDATES, ranked.size())));
        for (CutTag tag : CutTag.values()) {
            ranked.stream().filter(r -> r.cutTags().contains(tag)).findFirst().ifPresent(chosen::add);
        }
        return ranked.stream().filter(chosen::contains).limit(MAX_CANDIDATES).toList();
    }
}
