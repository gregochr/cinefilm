package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.library.FileNameParser;
import gregochr.cinefilm.library.FileNameParser.ParsedName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which film file a forced subtitle file belongs to from the two names alone. The subtitle's suffix is
 * removed, the rest is read as a film name by {@link FileNameParser}, and the file must have the same group key (the
 * folder, the title ignoring case, the year and the edition tag):
 * <ul>
 *   <li>a part tag ({@code - pt1}) picks that part's file;</li>
 *   <li>no part tag and a single untagged file: that file;</li>
 *   <li>no part tag and only part-tagged files (a split edition): the first part, as a whole-edition subtitle;</li>
 *   <li>anything else (no film by that name, or an ambiguous one): no match.</li>
 * </ul>
 * Pure.
 */
final class SidecarMatcher {

    /** A film file the subtitle may belong to. */
    record FileRef(Long id, String path) {
    }

    /** The match: the film file and whether the subtitle is timed for the whole split edition. */
    record Match(Long mediaFileId, boolean whole) {
    }

    private record Candidate(Long id, Integer partTag) {
    }

    private final Map<String, List<Candidate>> byGroup = new HashMap<>();

    /** Reads every film file's name once. */
    SidecarMatcher(List<FileRef> files) {
        for (FileRef file : files) {
            ParsedName parsed = FileNameParser.parse(file.path());
            byGroup.computeIfAbsent(parsed.groupKey(), k -> new ArrayList<>())
                .add(new Candidate(file.id(), parsed.partTag()));
        }
    }

    /**
     * The film file a subtitle belongs to.
     *
     * @param sidecarPath the subtitle's path relative to the library root
     * @return the match, or empty when it is unmatched
     */
    Optional<Match> match(String sidecarPath) {
        ParsedName parsed = FileNameParser.parse(SidecarNames.stem(sidecarPath) + ".m4v");
        List<Candidate> group = byGroup.getOrDefault(parsed.groupKey(), List.of());
        if (parsed.partTag() != null) {
            List<Candidate> same = group.stream().filter(c -> parsed.partTag().equals(c.partTag())).toList();
            return same.size() == 1 ? Optional.of(new Match(same.get(0).id(), false)) : Optional.empty();
        }
        if (group.size() == 1 && group.get(0).partTag() == null) {
            return Optional.of(new Match(group.get(0).id(), false));
        }
        if (!group.isEmpty() && group.stream().allMatch(c -> c.partTag() != null)) {
            Candidate first = group.stream().min(Comparator.comparingInt(Candidate::partTag)
                .thenComparing(Candidate::id)).orElseThrow();
            return Optional.of(new Match(first.id(), true));
        }
        return Optional.empty();
    }
}
