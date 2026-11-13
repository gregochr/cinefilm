package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.plex.PlexRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The saved Plex archive as L1 reads it.
 *
 * @param items the parsed items, by rating key in ascending order
 * @param listedWithoutItem rating keys that a section lists but that have no item file, in ascending order
 * @param unexpectedFiles files named {@code item-*} that are not {@code item-<digits>.json}, never parsed, sorted
 */
record PlexArchive(Map<Long, ArchiveItem> items, List<Long> listedWithoutItem, List<String> unexpectedFiles) {

    /**
     * Indexes the items by the path CineFilm stores for each of their files.
     *
     * @param prefix the Plex path prefix as {@link PlexRules#prefix(String)} returns it
     * @return for each relative path, the items that have it (each at most once, in rating-key order) with the version
     *     that holds it. Paths outside the prefix are not indexed.
     */
    Map<String, List<ArchiveMatch>> index(String prefix) {
        Map<String, List<ArchiveMatch>> index = new HashMap<>();
        for (ArchiveItem item : items.values()) {
            Set<String> seen = new HashSet<>();
            for (ArchiveItem.Media media : item.media()) {
                for (String file : media.files()) {
                    PlexRules.relativePath(prefix, file).filter(seen::add)
                        .ifPresent(path -> index.computeIfAbsent(path, p -> new ArrayList<>())
                            .add(new ArchiveMatch(item, media)));
                }
            }
        }
        return index;
    }
}
