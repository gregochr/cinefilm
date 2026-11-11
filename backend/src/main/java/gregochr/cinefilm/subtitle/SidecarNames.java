package gregochr.cinefilm.subtitle;

import java.util.List;
import java.util.Locale;

/** What a forced English subtitle beside a film is called: {@code <stem>.en.forced.srt} or {@code .forced.en.srt}. */
public final class SidecarNames {

    private static final List<String> SUFFIXES = List.of(".en.forced.srt", ".forced.en.srt");

    private SidecarNames() {
    }

    /**
     * True when a file name ends with one of the forced-subtitle suffixes (any case) and has a stem before it.
     *
     * @param fileName a file name, without folders
     * @return whether it names a forced English subtitle
     */
    public static boolean matches(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return SUFFIXES.stream().anyMatch(s -> lower.endsWith(s) && lower.length() > s.length());
    }

    /**
     * The path with the forced-subtitle suffix removed: the path of the film the subtitle is named after, without its
     * extension.
     *
     * @param path a path (or file name) for which {@link #matches} is true
     * @return the path without the suffix
     */
    public static String stem(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        throw new IllegalArgumentException("Not a forced subtitle name: " + path);
    }
}
