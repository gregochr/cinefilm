package gregochr.cinefilm.plex;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.FileNameParser;
import gregochr.cinefilm.library.FileNameParser.ParsedName;
import java.util.Collection;
import java.util.List;

/**
 * The runtime signal of the different-film guard: untagged versions of one title whose runtimes differ by more than
 * 15% are only a warning sign (untagged theatrical and extended cuts routinely differ that much, and the titles
 * already agree): the Plex import flags them and never blocks on them. It never part-joins untagged versions.
 */
final class RuntimeSignal {

    private static final int LIMIT_PERCENT = 15;

    private RuntimeSignal() {
    }

    /** True when both runtimes are known and differ by more than 15% of the longer one. */
    static boolean differs(long a, long b) {
        return a > 0 && b > 0 && Math.abs(a - b) * 100 > LIMIT_PERCENT * Math.max(a, b);
    }

    /** The report line for two files whose runtimes differ; the names are alphabetical, so a pair reads alike. */
    static String describe(long a, long b, String pathA, String pathB) {
        long percent = Math.round(Math.abs(a - b) * 100.0 / Math.max(a, b));
        String first = name(pathA);
        String second = name(pathB);
        if (first.compareTo(second) > 0) {
            String swap = first;
            first = second;
            second = swap;
        }
        return "Runtimes differ by " + percent + "% — check these are the same film: \"" + first
            + "\" and \"" + second + "\"";
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

    /** True when there is at least one part and every part has a positive duration (a sum is only then a runtime). */
    static boolean durationsKnown(Collection<MediaFile> parts) {
        return !parts.isEmpty() && parts.stream().allMatch(p -> p.getDurationMs() != null && p.getDurationMs() > 0);
    }

    private static boolean comparable(Edition edition) {
        List<MediaFile> parts = edition.originalParts();
        List<String> paths = parts.stream().map(f -> f.getPath()).toList();
        if (paths.isEmpty() || !durationsKnown(parts) || !untagged(paths)) {
            return false;
        }
        return paths.size() > 1 || FileNameParser.parse(paths.get(0)).partTag() == null;
    }

    private static String name(String path) {
        return path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
    }
}
