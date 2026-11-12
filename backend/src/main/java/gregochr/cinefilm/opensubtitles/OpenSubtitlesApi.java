package gregochr.cinefilm.opensubtitles;

/**
 * What CineFilm asks of OpenSubtitles: search, and download of a subtitle file. An outage is an
 * {@link OpenSubtitlesUnavailableException}, never a half answer and never "no results".
 */
public interface OpenSubtitlesApi {

    /** True when an API key is configured, so searches can be attempted. */
    boolean enabled();

    /** True when downloads can be attempted: an API key and the account's username and password are configured. */
    boolean downloadsEnabled();

    /**
     * Searches for English foreign-parts-only subtitles of a film.
     *
     * @param imdbId the IMDb id, for example {@code tt0936501}
     * @return how many usable results there are and the best of them
     * @throws IllegalArgumentException when the id is not {@code tt} and 1 to 10 digits
     * @throws OpenSubtitlesUnavailableException when OpenSubtitles cannot answer or no key is configured
     */
    OpenSubtitlesSearch searchForced(String imdbId);

    /**
     * Downloads one subtitle file (a search result's {@code fileId}). Logs in first when there is no session, and
     * once more after a 401. The link OpenSubtitles answers with is fetched only when it is {@code https} on a
     * {@code *.opensubtitles.com} host, without following redirects, and at most 2 MB of it is read.
     *
     * @param fileId the file id
     * @return the subtitle read as SRT, and the allowance left
     * @throws IllegalArgumentException when the id is not positive
     * @throws OpenSubtitlesQuotaException when the download allowance is used up
     * @throws OpenSubtitlesChargedException when the link was issued (the account is charged) but the file could not
     *     be fetched or was refused: never to be given back to a budget
     * @throws OpenSubtitlesUnavailableException when OpenSubtitles cannot answer, answers something unexpected, or
     *     downloads are off (nothing was charged)
     */
    OpenSubtitlesDownload download(long fileId);
}
