package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearch;
import java.util.OptionalInt;

/**
 * A stand-in for {@link OpenSubtitlesApi} that only answers the two on/off questions (search on, downloads on); it
 * never talks to anything. Tests that decide "being checked" set the switches they need.
 */
final class OpenSubtitlesSwitches implements OpenSubtitlesApi {

    private final boolean search;
    private final boolean downloads;

    OpenSubtitlesSwitches(boolean search, boolean downloads) {
        this.search = search;
        this.downloads = downloads;
    }

    /** Search and downloads both on: the usual setup. */
    static OpenSubtitlesSwitches on() {
        return new OpenSubtitlesSwitches(true, true);
    }

    @Override
    public boolean enabled() {
        return search;
    }

    @Override
    public boolean downloadsEnabled() {
        return downloads;
    }

    @Override
    public OpenSubtitlesSearch searchForced(String imdbId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public OpenSubtitlesSearch searchFull(String imdbId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public OpenSubtitlesDownload download(long fileId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public OptionalInt allowedDownloads() {
        throw new UnsupportedOperationException();
    }
}
