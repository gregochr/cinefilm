package gregochr.cinefilm.plex;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.library.FileNameParser;
import gregochr.cinefilm.library.FileNameParser.ParsedName;
import java.util.Collection;
import java.util.List;

/**
 * The runtime signal of the different-film guard: untagged versions of one title whose runtimes differ by more than
 * 15% are probably not the same film, so the Plex import never joins them and flags them instead.
 */
final class RuntimeSignal {

    private static final int LIMIT_PERCENT = 15;

    private RuntimeSignal() {
    }

    /** True when both runtimes are known and differ by more than 15% of the longer one. */
    static boolean differs(long a, long b) {
        return a > 0 && b > 0 && Math.abs(a - b) * 100 > LIMIT_PERCENT * Math.max(a, b);
    }

    /** The report line for two files whose runtimes differ. */
    static String describe(long a, long b, String pathA, String pathB) {
        long percent = Math.round(Math.abs(a - b) * 100.0 / Math.max(a, b));
        return "Runtimes differ by " + percent + "% — check these are the same film: \"" + name(pathA)
            + "\" and \"" + name(pathB) + "\"";
    }

    /** True when no path carries an edition tag or a plain edition word (they say which cut it is). */
    static boolean untagged(List<String> paths) {
        for (String path : paths) {
            ParsedName parsed = FileNameParser.parse(path);
            if (parsed.editionTag() != null || parsed.plainEdition() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first pair of untagged editions, one from each side, whose runtimes differ too much, described; null when
     * there is none. A lone part of a split film has half a runtime and is never compared.
     */
    static String conflict(Collection<Edition> incoming, Collection<Edition> existing) {
        for (Edition a : incoming) {
            for (Edition b : existing) {
                if (!a.getId().equals(b.getId()) && comparable(a) && comparable(b)
                    && differs(a.getRuntimeMs(), b.getRuntimeMs())) {
                    return describe(a.getRuntimeMs(), b.getRuntimeMs(), a.originalFile().getPath(),
                        b.originalFile().getPath());
                }
            }
        }
        return null;
    }

    private static boolean comparable(Edition edition) {
        List<String> paths = edition.originalParts().stream().map(f -> f.getPath()).toList();
        if (paths.isEmpty() || !untagged(paths)) {
            return false;
        }
        return paths.size() > 1 || FileNameParser.parse(paths.get(0)).partTag() == null;
    }

    private static String name(String path) {
        return path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
    }
}
