package gregochr.cinefilm.opensubtitles;

/**
 * What a search for a film's English foreign-parts-only subtitles found, after dropping machine and AI translations
 * and other films' results. Only the first page of results is looked at.
 *
 * @param count how many results remain
 * @param best the top-ranked of them ({@link OpenSubtitlesRanking}), or null when there are none
 */
public record OpenSubtitlesSearch(int count, OpenSubtitlesResult best) {
}
