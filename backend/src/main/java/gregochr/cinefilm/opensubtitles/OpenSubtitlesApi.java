package gregochr.cinefilm.opensubtitles;

import java.util.OptionalInt;

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
     * Searches for full English subtitles of a film: foreign-parts-only files excluded, hearing-impaired files
     * included (and ranked last). The same reply handling as {@link #searchForced}; a search is not charged.
     *
     * @param imdbId the IMDb id, for example {@code tt0936501}
     * @return how many usable results there are, the best of them and the candidates a download may use
     * @throws IllegalArgumentException when the id is not {@code tt} and 1 to 10 digits
     * @throws OpenSubtitlesUnavailableException when OpenSubtitles cannot answer or no key is configured
     */
    OpenSubtitlesSearch searchFull(String imdbId);

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

    /**
     * The downloads a day this account is allowed, as its login reply says (20 for a free account, 1000 for a VIP
     * one). Logs in first when downloads are enabled and there is no session yet (a login is not a charged call); the
     * answer is kept until the session is renewed, so asking again costs nothing.
     *
     * @return the allowance, or empty when downloads are off, the login failed or the reply did not say
     */
    OptionalInt allowedDownloads();
}
