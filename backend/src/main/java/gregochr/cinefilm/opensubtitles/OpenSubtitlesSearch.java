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
 */
public record OpenSubtitlesSearch(int count, OpenSubtitlesResult best, List<OpenSubtitlesResult> candidates) {

    /** A search with no candidates listed (the best result alone is known). */
    public OpenSubtitlesSearch(int count, OpenSubtitlesResult best) {
        this(count, best, List.of());
    }
}
