package gregochr.cinefilm.plex;

import gregochr.cinefilm.library.LibraryScanner;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The import's pure decisions, as static functions so they are cheap to test and to mutation-test: which guid is the
 * TMDB id, which Plex file is which CineFilm file, which artwork path may be fetched.
 */
public final class PlexRules {

    private static final Pattern TMDB_GUID = Pattern.compile("tmdb://(\\d{1,18})");
    private static final Pattern ART_PATH = Pattern.compile("/library/metadata/\\d+/(thumb|art)/\\d+");

    private PlexRules() {
    }

    /**
     * The TMDB id among a film's guids ({@code tmdb://603}).
     *
     * @param guids the guid strings Plex lists (imdb, tvdb and plex guids are ignored)
     * @return the first positive TMDB id, or null when there is none
     */
    public static Long tmdbId(List<String> guids) {
        for (String guid : guids) {
            var matcher = TMDB_GUID.matcher(guid);
            if (matcher.matches()) {
                long id = Long.parseLong(matcher.group(1));
                if (id > 0) {
                    return id;
                }
            }
        }
        return null;
    }

    /** Unicode NFC, so a name Plex stores decomposed (macOS) pairs with the same name stored composed, and back. */
    public static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /** The configured path prefix, NFC and ending in exactly one slash (a blank prefix stays blank). */
    public static String prefix(String configured) {
        String prefix = nfc(configured == null ? "" : configured.strip());
        if (prefix.isEmpty()) {
            return prefix;
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    /**
     * Maps a Plex file path to the path CineFilm stores (relative to the library root), compared in NFC.
     *
     * @param prefix the result of {@link #prefix(String)}
     * @param plexFile the file path Plex reports
     * @return the relative path, or empty when the file is outside the prefix, under a {@code Plex Versions} folder,
     *     or has an empty, {@code .} or {@code ..} segment
     */
    public static Optional<String> relativePath(String prefix, String plexFile) {
        String file = nfc(plexFile);
        if (prefix.isEmpty() || !file.startsWith(prefix)) {
            return Optional.empty();
        }
        String relative = file.substring(prefix.length());
        for (String segment : relative.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                || segment.equals(LibraryScanner.PLEX_VERSIONS)) {
                return Optional.empty();
            }
        }
        return Optional.of(relative);
    }

    /**
     * Whether a {@code thumb} or {@code art} value may be handed to Plex's resizer. Only a plain library path
     * qualifies, so CineFilm can never ask Plex to fetch an arbitrary URL.
     */
    public static boolean isArtPath(String path) {
        return path != null && ART_PATH.matcher(path).matches();
    }
}
