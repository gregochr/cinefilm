package gregochr.cinefilm.plex;

import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.library.GroupingGuard;
import gregochr.cinefilm.plex.PlexFilmWriter.MediaWrite;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexMedia;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pairs one Plex film's versions and files with CineFilm's, behind the different-film guard. Pure apart from the claims
 * it records. Every Part of every Media is looked for; Plex's order is kept. A file whose comparison title is not the
 * film's is left out (never paired, never claimed), so it is matched on its own or by the Plex film it belongs to.
 */
final class PlexPairing {

    private PlexPairing() {
    }

    /**
     * The result of pairing one Plex film.
     *
     * @param media the versions with at least one paired file, in Plex's order
     * @param leftOut the paths (relative to the library root) the guard kept out of this film
     */
    record Result(List<MediaWrite> media, List<String> leftOut) {
    }

    /** One Part that is a file CineFilm has. */
    private record Found(int media, Known known, String title) {
    }

    /**
     * Pairs the film. The guard keeps the files whose comparison title equals the Plex film's, or, when there are
     * none, the files sharing the title of the first Media's first Part.
     *
     * @param film the Plex film
     * @param prefix the Plex path prefix, as {@link PlexRules#prefix(String)}
     * @param known CineFilm's files by NFC path
     * @param claimed ids of files an earlier Plex film already took; the files paired here are added
     * @return the paired versions and the files left out
     */
    static Result pair(PlexFilm film, String prefix, Map<String, Known> known, Set<Long> claimed) {
        List<PlexMedia> media = film.media();
        List<Found> found = new ArrayList<>();
        for (int m = 0; m < media.size(); m++) {
            for (String file : media.get(m).parts()) {
                Optional<Known> match = PlexRules.relativePath(prefix, file).map(known::get);
                if (match.isPresent() && !claimed.contains(match.get().id())) {
                    found.add(new Found(m, match.get(), GroupingGuard.titleOf(match.get().path())));
                }
            }
        }
        String keptTitle = keptTitle(film, prefix, found);
        List<String> leftOut = new ArrayList<>();
        List<MediaWrite> paired = new ArrayList<>();
        for (int m = 0; m < media.size(); m++) {
            List<Long> ids = new ArrayList<>();
            for (Found f : found) {
                if (f.media() != m) {
                    continue;
                }
                if (f.title().equals(keptTitle)) {
                    if (claimed.add(f.known().id())) {
                        ids.add(f.known().id());
                    }
                } else {
                    leftOut.add(f.known().path());
                }
            }
            if (!ids.isEmpty()) {
                paired.add(new MediaWrite(List.copyOf(ids), media.get(m).parts().size()));
            }
        }
        return new Result(paired, leftOut);
    }

    /** The comparison title the film's files must have: the Plex title's if any file has it, else the first Part's. */
    private static String keptTitle(PlexFilm film, String prefix, List<Found> found) {
        String plexTitle = GroupingGuard.titleOf(film.title() == null ? "" : film.title().replace('/', ' '));
        if (found.stream().anyMatch(f -> f.title().equals(plexTitle))) {
            return plexTitle;
        }
        if (film.media().isEmpty()) {
            return plexTitle;
        }
        String first = film.media().get(0).parts().get(0);
        return GroupingGuard.titleOf(PlexRules.relativePath(prefix, first).orElse(first));
    }
}
