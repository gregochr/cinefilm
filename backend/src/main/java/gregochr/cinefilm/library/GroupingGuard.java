package gregochr.cinefilm.library;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The different-film guard: two files may be grouped (as editions or as parts of one film) only if their comparison
 * titles (see {@link FileNameParser}) are identical. Films the owner settled ({@code editionsConfirmed}) are exempt.
 * Everything reads the path only, so the answer can be recomputed for any known file at any time.
 */
public final class GroupingGuard {

    private GroupingGuard() {
    }

    /** True when the two paths name the same film (identical comparison titles). */
    public static boolean sameFilm(String pathA, String pathB) {
        return title(pathA).equals(title(pathB));
    }

    /**
     * Whether a file may become part of a film. A confirmed film accepts anything; a film with no original files yet
     * accepts anything; otherwise the file's comparison title must equal that of one of the film's originals.
     *
     * @param target the film that would take the file
     * @param newPath the file's path relative to the library root
     * @return true when folding is allowed
     */
    public static boolean accepts(Film target, String newPath) {
        if (target.isEditionsConfirmed()) {
            return true;
        }
        Set<String> titles = titles(target);
        return titles.isEmpty() || titles.contains(title(newPath));
    }

    /**
     * Whether one film may be merged into another. Either being confirmed exempts the merge; otherwise every
     * comparison title of the film that goes away must be one the survivor already has.
     *
     * @param target the film that would keep everything
     * @param source the film that would go away
     * @return true when merging is allowed
     */
    public static boolean accepts(Film target, Film source) {
        if (target.isEditionsConfirmed() || source.isEditionsConfirmed()) {
            return true;
        }
        Set<String> into = titles(target);
        Set<String> from = titles(source);
        return into.isEmpty() || from.isEmpty() || into.containsAll(from);
    }

    /** The comparison titles of a film's original files, whatever their presence. */
    static Set<String> titles(Film film) {
        Set<String> titles = new LinkedHashSet<>();
        for (Edition edition : film.getEditions()) {
            for (MediaFile file : edition.originalParts()) {
                titles.add(title(file.getPath()));
            }
        }
        return titles;
    }

    /**
     * The comparison title of a path, or of a plain title (the Plex import compares a Plex film's title with its
     * files'; a slash in a plain title must be replaced first, it would read as a folder).
     */
    public static String titleOf(String pathOrTitle) {
        return title(pathOrTitle);
    }

    private static String title(String path) {
        return FileNameParser.parse(path).comparisonTitle();
    }
}
