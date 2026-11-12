package gregochr.cinefilm.opensubtitles;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
}
