package gregochr.cinefilm.library;

import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
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
 * groups then move to films of their own. A group also counts as different when TMDB shows it is a different film
 * ({@link #isDifferentFilm}); the caller looks that up for the groups {@link #lookups} names and passes the result to
 * {@link #decide(String, Long, List, Set)}. Otherwise the film is flagged: "Files look like different films".
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
        return decide(filmTitle, filmTmdbId, files, Set.of());
    }

    /**
     * Judges one film, with TMDB's evidence.
     *
     * @param filmTitle the film's title
     * @param filmTmdbId the film's TMDB id, or null
     * @param files every original file of the film, whatever its presence
     * @param differentFilm the comparison titles of the groups TMDB shows are different films
     * @return the decision
     */
    public static Decision decide(String filmTitle, Long filmTmdbId, List<FileFact> files,
                                  Set<String> differentFilm) {
        Map<String, List<FileFact>> byTitle = byTitle(files);
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
            strong &= otherTag || sequelDiffers(own, entry.getKey())
                || reference != null && differentFilm.contains(entry.getKey());
        }
        if (strong) {
            return new Decision(Kind.SPLIT, List.copyOf(others), null);
        }
        String summary = "Files look like different films: " + String.join(", ", shown) + " in \"" + filmTitle + "\"";
        return new Decision(Kind.FLAG, List.of(), summary.substring(0, Math.min(summary.length(), SUMMARY_MAX)));
    }

    private static Map<String, List<FileFact>> byTitle(List<FileFact> files) {
        Map<String, List<FileFact>> byTitle = new LinkedHashMap<>();
        files.stream().sorted(java.util.Comparator.comparing(FileFact::path))
            .forEach(f -> byTitle.computeIfAbsent(f.parsed().comparisonTitle(), k -> new ArrayList<>()).add(f));
        return byTitle;
    }

    /**
     * The groups worth asking TMDB about. Empty unless today's decision is FLAG, the film has an own group and a
     * reference id. A group is returned when it lacks today's evidence (a sequel marker, a differing {@code {tmdb-}}
     * tag) and is eligible: no {@code {tmdb-}} or {@code {imdb-}} tag on any file (the owner's word is never
     * overruled by a title search) and no edition tag or plain edition word (TMDB lists some cuts as films of their
     * own).
     *
     * @param filmTitle the film's title
     * @param filmTmdbId the film's TMDB id, or null
     * @param files every original file of the film
     * @return the groups to look up, in path order
     */
    public static List<TitleGroup> lookups(String filmTitle, Long filmTmdbId, List<FileFact> files) {
        if (decide(filmTitle, filmTmdbId, files).kind() != Kind.FLAG) {
            return List.of();
        }
        Map<String, List<FileFact>> byTitle = byTitle(files);
        String own = FileNameParser.comparisonTitle(filmTitle);
        Long reference = reference(filmTitle, filmTmdbId, files);
        if (byTitle.get(own) == null || reference == null) {
            return List.of();
        }
        List<TitleGroup> lookups = new ArrayList<>();
        for (Map.Entry<String, List<FileFact>> entry : byTitle.entrySet()) {
            List<FileFact> group = entry.getValue();
            Long tag = taggedId(group);
            boolean today = tag != null && !tag.equals(reference) || sequelDiffers(own, entry.getKey());
            if (!entry.getKey().equals(own) && !today && eligible(group)) {
                FileFact first = group.get(0);
                lookups.add(new TitleGroup(entry.getKey(), first.parsed().title(), first.parsed().year(), group));
            }
        }
        return lookups;
    }

    /** The film's reference TMDB id: its own, else the id its own group's files are all tagged with, else null. */
    static Long reference(String filmTitle, Long filmTmdbId, List<FileFact> files) {
        List<FileFact> ownFiles = byTitle(files).get(FileNameParser.comparisonTitle(filmTitle));
        return filmTmdbId != null ? filmTmdbId : ownFiles == null ? null : taggedId(ownFiles);
    }

    private static boolean eligible(List<FileFact> group) {
        return group.stream().noneMatch(f -> f.parsed().tmdbId() != null || f.parsed().imdbId() != null
            || f.parsed().editionTag() != null || f.parsed().plainEdition() != null);
    }

    /**
     * True when TMDB shows the group is a different film from the reference: some hit's title or original title is
     * the group's title (the same {@link FileNameParser#comparisonTitle}), no such hit is the reference film, and the
     * group's title is not one of the reference film's own titles.
     *
     * @param groupTitle the group's comparison title
     * @param hits the TMDB search hits for the group's title
     * @param referenceId the film's reference TMDB id
     * @param referenceTitles the reference film's titles (title, original title, alternative titles), as written
     * @return true when TMDB lists it as a different film
     */
    public static boolean isDifferentFilm(String groupTitle, List<SearchHit> hits, long referenceId,
                                          Set<String> referenceTitles) {
        boolean exact = false;
        for (SearchHit hit : hits) {
            if (exactHit(hit, groupTitle)) {
                if (hit.id() == referenceId) {
                    return false;
                }
                exact = true;
            }
        }
        return exact && referenceTitles.stream().noneMatch(t -> titled(t, groupTitle));
    }

    /** True when the hit's title or original title is the comparison title. */
    static boolean exactHit(SearchHit hit, String comparisonTitle) {
        return titled(hit.title(), comparisonTitle) || titled(hit.originalTitle(), comparisonTitle);
    }

    private static boolean titled(String title, String comparisonTitle) {
        return title != null && FileNameParser.comparisonTitle(title).equals(comparisonTitle);
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
