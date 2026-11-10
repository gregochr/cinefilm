package gregochr.cinefilm.library;

import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides what the scan's auto-resolve joins. Pure: it reads {@link FileFact}s (only films the owner has not settled
 * appear in them) and answers with plans; {@link LibraryGrouping} carries them out.
 *
 * <ul>
 *   <li><strong>Film merges:</strong> files with the same folder, comparison title and year but different edition tags
 *       are one film with several editions (Gladiator's two cuts are one tile even with TMDB down).</li>
 *   <li><strong>Part joins:</strong> files with the same group key (folder, title, year, edition tag) that are all
 *       part-tagged with numbers 1..n and no duplicates are one edition. Untagged and tagged files of one key are not
 *       joined.</li>
 * </ul>
 * Only {@link GroupingSource#AUTO} editions are ever touched.
 */
public final class EditionGrouper {

    private EditionGrouper() {
    }

    /**
     * The films to merge, each list holding the film that survives first (a matched film, else the oldest) and then the
     * films that go into it. A film appears in at most one plan per call.
     *
     * @param facts every original file of a film the owner has not settled
     * @return the merges, in the order the files were given
     */
    public static List<List<Long>> filmMerges(List<FileFact> facts) {
        Map<String, List<FileFact>> byKey = new LinkedHashMap<>();
        for (FileFact fact : facts) {
            byKey.computeIfAbsent(fact.filmKey(), k -> new ArrayList<>()).add(fact);
        }
        List<List<Long>> plans = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (List<FileFact> group : byKey.values()) {
            Set<Long> filmIds = new TreeSet<>();
            Set<String> tags = new LinkedHashSet<>();
            boolean allAuto = true;
            for (FileFact fact : group) {
                filmIds.add(fact.filmId());
                tags.add(fact.tagKey());
                allAuto &= fact.editionSource() == GroupingSource.AUTO;
            }
            if (filmIds.size() < 2 || tags.size() < 2 || !allAuto || filmIds.stream().anyMatch(used::contains)) {
                continue;
            }
            long survivor = group.stream().filter(f -> f.filmStatus() == MatchStatus.MATCHED)
                .map(FileFact::filmId).min(Comparator.naturalOrder()).orElse(filmIds.iterator().next());
            List<Long> plan = new ArrayList<>();
            plan.add(survivor);
            filmIds.stream().filter(id -> id != survivor).forEach(plan::add);
            used.addAll(filmIds);
            plans.add(plan);
        }
        return plans;
    }

    /**
     * The files to join into one edition, each list in play order (the first file's edition survives).
     *
     * @param facts every original file of a film the owner has not settled
     * @return the joins
     */
    public static List<List<Long>> partJoins(List<FileFact> facts) {
        Map<String, List<FileFact>> byKey = new LinkedHashMap<>();
        Map<Long, Long> filesPerEdition = new LinkedHashMap<>();
        for (FileFact fact : facts) {
            byKey.computeIfAbsent(fact.parsed().groupKey(), k -> new ArrayList<>()).add(fact);
            filesPerEdition.merge(fact.editionId(), 1L, Long::sum);
        }
        List<List<Long>> plans = new ArrayList<>();
        for (List<FileFact> group : byKey.values()) {
            List<FileFact> ordered = joinable(group, filesPerEdition);
            if (ordered != null) {
                plans.add(ordered.stream().map(FileFact::fileId).toList());
            }
        }
        return plans;
    }

    /** The group sorted by part, or null when it must not be joined. */
    private static List<FileFact> joinable(List<FileFact> group, Map<Long, Long> filesPerEdition) {
        Set<Long> editions = new LinkedHashSet<>();
        Map<Long, Long> inGroup = new LinkedHashMap<>();
        for (FileFact fact : group) {
            if (fact.editionSource() != GroupingSource.AUTO || fact.parsed().partTag() == null || !fact.joinable()) {
                return null;
            }
            editions.add(fact.editionId());
            inGroup.merge(fact.editionId(), 1L, Long::sum);
        }
        if (group.size() < 2 || editions.size() < 2) {
            return null;
        }
        // Every file of every edition involved must be in the group: nothing else gets swept into the join or lost.
        for (Map.Entry<Long, Long> entry : inGroup.entrySet()) {
            if (!entry.getValue().equals(filesPerEdition.get(entry.getKey()))) {
                return null;
            }
        }
        List<FileFact> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingInt((FileFact f) -> f.parsed().partTag()).thenComparingLong(FileFact::fileId));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).parsed().partTag() != i + 1) {
                return null;
            }
        }
        return sorted;
    }
}
