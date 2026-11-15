package gregochr.cinefilm.opensubtitles;

import java.util.List;

/**
 * What a search for a film's English foreign-parts-only subtitles found, after dropping machine and AI translations
 * and other films' results. Only the first page of results is looked at.
 *
 * @param count how many results remain
 * @param best the top-ranked of them ({@link OpenSubtitlesRanking}), or null when there are none
 * @param candidates the single-file results the check may compare with, in ranked order
 *     ({@link OpenSubtitlesRanking#candidates}); never null
 * @param results every result that remains, in the order OpenSubtitles listed them (multi-file ones included), for the
 *     Admin picker; never null
 */
public record OpenSubtitlesSearch(int count, OpenSubtitlesResult best, List<OpenSubtitlesResult> candidates,
                                  List<OpenSubtitlesResult> results) {

    /** A search whose results are its candidates (the scheduler's searches never look at the rest). */
    public OpenSubtitlesSearch(int count, OpenSubtitlesResult best, List<OpenSubtitlesResult> candidates) {
        this(count, best, candidates, candidates);
    }

    /** A search with no candidates listed (the best result alone is known). */
    public OpenSubtitlesSearch(int count, OpenSubtitlesResult best) {
        this(count, best, List.of(), List.of());
    }
}
