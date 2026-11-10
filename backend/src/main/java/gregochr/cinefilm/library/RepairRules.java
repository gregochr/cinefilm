package gregochr.cinefilm.library;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Library repair, the deciding half (pure). A film whose original files have different comparison titles is either
 * split, on strong evidence, or flagged for the owner; it is never guessed at.
 *
 * <p>Split when exactly one same-title group's comparison title equals the film's own and every other group differs
 * from it by a sequel marker (a number, numeral, "Vol" or "Part" token) or by its own {@code {tmdb-}} tag. The other
 * groups then move to films of their own. Otherwise the film is flagged: "Files look like different films".
 */
public final class RepairRules {

    private static final Set<String> MARKER_WORDS = Set.of("vol", "volume", "part", "pt");
    private static final int SUMMARY_MAX = 500;

    private RepairRules() {
    }

    /** What to do with a film. */
    public enum Kind {
        /** Every file has the same comparison title: leave it. */
        NONE,
        /** Move the other title groups out. */
        SPLIT,
        /** Leave it, and tell the owner. */
        FLAG
    }

    /**
     * Files of one film that share a comparison title.
     *
     * @param comparisonTitle the shared comparison title
     * @param title the display title of the first file
     * @param year the first file's year, or null
     * @param files the files, in path order
     */
    public record TitleGroup(String comparisonTitle, String title, Integer year, List<FileFact> files) {
    }

    /**
     * The verdict on one film.
     *
     * @param kind what to do
     * @param others for a split, the groups that leave (the film keeps the rest); otherwise empty
     * @param summary for a flag, the line to log; otherwise null
     */
    public record Decision(Kind kind, List<TitleGroup> others, String summary) {
    }

    /**
     * Judges one film.
     *
     * @param filmTitle the film's title
     * @param filmTmdbId the film's TMDB id, or null
     * @param files every original file of the film, whatever its presence
     * @return the decision
     */
    public static Decision decide(String filmTitle, Long filmTmdbId, List<FileFact> files) {
        Map<String, List<FileFact>> byTitle = new LinkedHashMap<>();
        files.stream().sorted(java.util.Comparator.comparing(FileFact::path))
            .forEach(f -> byTitle.computeIfAbsent(f.parsed().comparisonTitle(), k -> new ArrayList<>()).add(f));
        if (byTitle.size() < 2) {
            return new Decision(Kind.NONE, List.of(), null);
        }
        String own = FileNameParser.comparisonTitle(filmTitle);
        List<FileFact> ownFiles = byTitle.get(own);
        List<TitleGroup> others = new ArrayList<>();
        List<String> shown = new ArrayList<>();
        boolean strong = ownFiles != null;
        Long reference = filmTmdbId != null ? filmTmdbId : ownFiles == null ? null : taggedId(ownFiles);
        for (Map.Entry<String, List<FileFact>> entry : byTitle.entrySet()) {
            FileFact first = entry.getValue().get(0);
            shown.add("\"" + first.parsed().title() + "\"");
            if (entry.getKey().equals(own)) {
                continue;
            }
            others.add(new TitleGroup(entry.getKey(), first.parsed().title(), first.parsed().year(),
                entry.getValue()));
            Long tag = taggedId(entry.getValue());
            boolean otherTag = tag != null && !tag.equals(reference);
            strong &= otherTag || sequelDiffers(own, entry.getKey());
        }
        if (strong) {
            return new Decision(Kind.SPLIT, List.copyOf(others), null);
        }
        String summary = "Files look like different films: " + String.join(", ", shown) + " in \"" + filmTitle + "\"";
        return new Decision(Kind.FLAG, List.of(), summary.substring(0, Math.min(summary.length(), SUMMARY_MAX)));
    }

    /** The one TMDB id every file of the group is tagged with, or null when any file is untagged or they disagree. */
    private static Long taggedId(List<FileFact> files) {
        Long id = files.get(0).parsed().tmdbId();
        for (FileFact file : files) {
            if (!Objects.equals(id, file.parsed().tmdbId())) {
                return null;
            }
        }
        return id;
    }

    /**
     * True when the two comparison titles are the same words apart from sequel markers, and the numbers differ: "rocky
     * 4" and "rocky 5", "karate kid" and "karate kid 2", "kill bill vol 1" and "kill bill vol 2".
     */
    static boolean sequelDiffers(String a, String b) {
        List<String> numbersA = new ArrayList<>();
        List<String> numbersB = new ArrayList<>();
        return base(a, numbersA).equals(base(b, numbersB)) && !numbersA.equals(numbersB);
    }

    private static String base(String title, List<String> numbers) {
        List<String> words = new ArrayList<>();
        for (String word : title.split(" ")) {
            if (word.chars().allMatch(Character::isDigit)) {
                numbers.add(word);
            } else if (!MARKER_WORDS.contains(word)) {
                words.add(word);
            }
        }
        return String.join(" ", words);
    }
}
