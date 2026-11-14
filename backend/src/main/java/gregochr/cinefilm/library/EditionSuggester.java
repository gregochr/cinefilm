package gregochr.cinefilm.library;

import gregochr.cinefilm.library.FileNameParser.ParsedName;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Suggests how a film's files belong in editions and parts, and says why in one line. Pure: nothing is read or
 * written. Rules, in order: files the owner has already arranged are shown as arranged; files whose names carry a
 * different film's title are suggested as not this film; edition tags, plain edition words and part suffixes come
 * next; untagged files are told apart by runtime (more than five minutes apart: shorter is Theatrical, longer is
 * Extended; otherwise copies of one cut, kept apart as Original, Original 2).
 */
public final class EditionSuggester {

    /** The reason when tags, edition words or part numbers decided it. */
    public static final String FROM_TAGS = "From the edition tags and part numbers";
    /** The reason for a film the owner has already settled. */
    public static final String AS_ARRANGED = "As you last arranged it";

    /** Runtimes further apart than this are different cuts. */
    static final long DIFFERENT_CUTS_MS = 5 * 60_000L;
    /** Runtimes within this of each other are copies of one cut. */
    static final long SAME_CUT_MS = 60_000L;

    private static final String ORIGINAL = "Original";
    private static final String KEPT_APART = "Kept apart so you can compare; rename one, or mark it Not this film.";

    private static final String MOVE_BACK = " — if it's the same film, move it back to keep it here";

    private EditionSuggester() {
    }

    /**
     * One file as the suggester sees it.
     *
     * @param id media file id
     * @param path the path below the library root
     * @param durationMs the container duration, or null when unknown
     * @param editionId the edition the file is in now
     * @param editionName that edition's name
     * @param partNumber the file's part number now
     */
    public record SuggestFile(Long id, String path, Long durationMs, Long editionId, String editionName,
                              int partNumber) {
    }

    /**
     * A file in a suggested edition.
     *
     * @param fileId the media file id
     * @param part the part number, or null for a single file
     */
    public record Slot(Long fileId, Integer part) {
    }

    /**
     * A suggested edition.
     *
     * @param editionId the existing edition it continues (so its history stays), or null for a new one
     * @param name its name
     * @param files its files in part order
     */
    public record Group(Long editionId, String name, List<Slot> files) {
    }

    /**
     * The suggestion.
     *
     * @param reason one line saying why, or empty when a single file leaves nothing to say
     * @param editions the suggested editions
     * @param notThisFilm files suggested as not this film
     * @param defaultName the edition suggested as the default (null when there are no editions)
     */
    public record Suggestion(String reason, List<Group> editions, List<Long> notThisFilm, String defaultName) {
    }

    private record Parsed(SuggestFile file, ParsedName name) {
    }

    /** A group being built: the name is null until names are made unique. */
    private record Draft(String name, List<Parsed> members) {
    }

    private record Outcome(List<Draft> drafts, String reason) {
    }

    /**
     * Suggests an arrangement.
     *
     * @param filmTitle the film's title (a file whose name carries another film's title is not this film)
     * @param settled true when the owner has already settled the film: the arrangement is then shown as it is
     * @param currentDefaultId the edition the film opens on now, or null
     * @param files the film's original files, in a stable order (by edition, part, then id)
     * @return the suggestion
     */
    public static Suggestion suggest(String filmTitle, boolean settled, Long currentDefaultId,
                                     List<SuggestFile> files) {
        if (files.isEmpty()) {
            return new Suggestion("", List.of(), List.of(), null);
        }
        List<Parsed> all = files.stream().map(f -> new Parsed(f, FileNameParser.parse(f.path()))).toList();
        if (settled) {
            return settled(all, currentDefaultId);
        }
        String own = FileNameParser.comparisonTitle(filmTitle);
        boolean ownPresent = all.stream().anyMatch(p -> p.name().comparisonTitle().equals(own));
        List<Parsed> kept = ownPresent
            ? all.stream().filter(p -> p.name().comparisonTitle().equals(own)).toList() : all;
        List<Parsed> excluded = ownPresent
            ? all.stream().filter(p -> !p.name().comparisonTitle().equals(own)).toList() : List.of();
        Outcome outcome = arrange(kept);
        String reason = excluded.isEmpty() ? outcome.reason() : differentFilms(kept, excluded);
        List<Group> groups = groups(outcome.drafts(), files);
        return new Suggestion(reason, groups, excluded.stream().map(p -> p.file().id()).toList(),
            defaultName(groups, files, currentDefaultId));
    }

    private static Suggestion settled(List<Parsed> all, Long currentDefaultId) {
        Map<Long, List<Parsed>> byEdition = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing((Parsed p) -> p.file().editionId())
                .thenComparingInt(p -> p.file().partNumber()).thenComparing(p -> p.file().id()))
            .forEach(p -> byEdition.computeIfAbsent(p.file().editionId(), k -> new ArrayList<>()).add(p));
        List<Group> groups = new ArrayList<>();
        byEdition.forEach((id, members) -> groups.add(new Group(id, members.get(0).file().editionName(),
            members.stream().map(p -> new Slot(p.file().id(), members.size() > 1 ? p.file().partNumber() : null))
                .toList())));
        List<SuggestFile> files = all.stream().map(Parsed::file).toList();
        return new Suggestion(AS_ARRANGED, groups, List.of(), defaultName(groups, files, currentDefaultId));
    }

    private static Outcome arrange(List<Parsed> kept) {
        boolean tagged = kept.stream().anyMatch(p -> p.name().editionTag() != null || p.name().partTag() != null
            || p.name().plainEdition() != null);
        if (tagged) {
            return new Outcome(tagged(kept), FROM_TAGS);
        }
        if (kept.size() == 1) {
            return new Outcome(List.of(new Draft(null, kept)), "");
        }
        return byRuntime(kept);
    }

    /** Groups by edition tag, else plain edition word; part-only files share one group; the rest stand alone. */
    private static List<Draft> tagged(List<Parsed> kept) {
        Map<String, List<Parsed>> groups = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        for (Parsed p : kept) {
            String name = p.name().editionTag() != null ? p.name().editionTag() : p.name().plainEdition();
            String key = name != null ? name.toLowerCase(Locale.ROOT)
                : p.name().partTag() != null ? "" : "#" + p.file().id();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            names.putIfAbsent(key, name);
        }
        List<Draft> drafts = new ArrayList<>();
        groups.forEach((key, members) -> drafts.add(new Draft(names.get(key), members)));
        return drafts;
    }

    private static Outcome byRuntime(List<Parsed> kept) {
        List<Parsed> known = kept.stream().filter(p -> duration(p) > 0)
            .sorted(Comparator.comparingLong(EditionSuggester::duration)).toList();
        List<Parsed> unknown = kept.stream().filter(p -> duration(p) <= 0).toList();
        if (known.size() < 2) {
            return new Outcome(alone(kept), "Runtimes aren't known for every file — " + lower(KEPT_APART));
        }
        List<List<Parsed>> clusters = new ArrayList<>();
        long previous = 0;
        for (Parsed p : known) {
            if (clusters.isEmpty() || duration(p) - previous > DIFFERENT_CUTS_MS) {
                clusters.add(new ArrayList<>());
            }
            clusters.get(clusters.size() - 1).add(p);
            previous = duration(p);
        }
        long shortest = duration(known.get(0));
        long longest = duration(known.get(known.size() - 1));
        if (clusters.size() == 2) {
            List<Draft> drafts = new ArrayList<>();
            clusters.get(0).forEach(p -> drafts.add(new Draft("Theatrical", List.of(p))));
            clusters.get(1).forEach(p -> drafts.add(new Draft("Extended", List.of(p))));
            unknown.forEach(p -> drafts.add(new Draft(null, List.of(p))));
            return new Outcome(drafts, "Runtimes " + minutes(shortest) + " and "
                + minutes(duration(clusters.get(1).get(0))) + " — likely Theatrical and Extended");
        }
        if (clusters.size() == 1) {
            String reason = longest - shortest <= SAME_CUT_MS
                ? "Runtimes match (" + minutes(shortest) + ") — likely copies of one cut. " + KEPT_APART
                : "Runtimes are close (" + minutes(shortest) + " and " + minutes(longest)
                    + ") — likely copies of one cut. " + KEPT_APART;
            return new Outcome(alone(kept), reason);
        }
        List<String> runtimes = known.stream().map(p -> minutes(duration(p))).toList();
        return new Outcome(alone(kept), "Runtimes " + String.join(", ", runtimes)
            + " — kept apart so you can compare; rename them, or mark any that aren't this film.");
    }

    private static List<Draft> alone(List<Parsed> members) {
        return members.stream().map(p -> new Draft(null, List.of(p))).toList();
    }

    private static String lower(String sentence) {
        return Character.toLowerCase(sentence.charAt(0)) + sentence.substring(1);
    }

    private static long duration(Parsed p) {
        return p.file().durationMs() == null ? 0 : p.file().durationMs();
    }

    private static String differentFilms(List<Parsed> kept, List<Parsed> excluded) {
        long a = duration(kept.get(0));
        long b = duration(excluded.get(0));
        if (a <= 0 || b <= 0) {
            return "These files may be different films";
        }
        long shorter = Math.min(a, b);
        long longer = Math.max(a, b);
        if (longer - shorter <= SAME_CUT_MS) {
            return "The names differ but the runtimes match (" + minutes(shorter) + ")" + MOVE_BACK;
        }
        if (longer - shorter <= DIFFERENT_CUTS_MS) {
            return "The names differ but the runtimes are close (" + minutes(shorter) + " and " + minutes(longer)
                + ")" + MOVE_BACK;
        }
        return "Runtimes " + minutes(shorter) + " and " + minutes(longer) + " — these may be different films";
    }

    /** Hours and minutes as the panel shows them: {@code 2h 35m}, or {@code 45m} under an hour. */
    static String minutes(long ms) {
        long total = ms / 60_000;
        return total >= 60 ? total / 60 + "h " + total % 60 + "m" : total + "m";
    }

    /** Names the drafts (unique, case-insensitively), numbers their parts and matches them to existing editions. */
    private static List<Group> groups(List<Draft> drafts, List<SuggestFile> files) {
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (Draft draft : drafts) {
            names.add(draft.name() == null ? null : unique(draft.name(), used));
        }
        for (int i = 0; i < drafts.size(); i++) {
            if (names.get(i) == null) {
                names.set(i, unique(ORIGINAL, used));
            }
        }
        List<Long> ids = matchEditions(drafts, files);
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            List<Parsed> members = drafts.get(i).members().stream().sorted(Comparator
                .comparing((Parsed p) -> p.name().partTag(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(p -> p.file().id())).toList();
            List<Slot> slots = members.size() == 1 ? List.of(new Slot(members.get(0).file().id(), null))
                : members.stream().map(p -> new Slot(p.file().id(), p.name().partTag())).toList();
            groups.add(new Group(ids.get(i), names.get(i), slots));
        }
        return groups;
    }

    private static String unique(String base, Set<String> used) {
        String name = base;
        for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
            name = base + " " + n;
        }
        return name;
    }

    /** Each draft continues the existing edition holding most of its files; an edition continues only one draft. */
    private static List<Long> matchEditions(List<Draft> drafts, List<SuggestFile> files) {
        Map<Long, Long> editionOf = new HashMap<>();
        files.forEach(f -> editionOf.put(f.id(), f.editionId()));
        record Pair(int draft, Long edition, int shared) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Map<Long, Integer> counts = new HashMap<>();
            for (Parsed member : drafts.get(i).members()) {
                counts.merge(editionOf.get(member.file().id()), 1, Integer::sum);
            }
            int draft = i;
            counts.forEach((edition, shared) -> pairs.add(new Pair(draft, edition, shared)));
        }
        pairs.sort(Comparator.comparingInt(Pair::shared).reversed().thenComparing(Pair::edition)
            .thenComparingInt(Pair::draft));
        List<Long> ids = new ArrayList<>();
        drafts.forEach(d -> ids.add(null));
        Set<Long> taken = new HashSet<>();
        for (Pair pair : pairs) {
            if (ids.get(pair.draft()) == null && taken.add(pair.edition())) {
                ids.set(pair.draft(), pair.edition());
            }
        }
        return ids;
    }

    /**
     * The default: the edition the film opens on now when it is still there, else Original or Theatrical, else the
     * longest.
     */
    private static String defaultName(List<Group> groups, List<SuggestFile> files, Long currentDefaultId) {
        for (Group group : groups) {
            if (group.editionId() != null && group.editionId().equals(currentDefaultId)) {
                return group.name();
            }
        }
        Map<Long, Long> durations = new HashMap<>();
        files.forEach(f -> durations.put(f.id(), f.durationMs() == null ? 0 : f.durationMs()));
        for (Group group : groups) {
            String lower = group.name().toLowerCase(Locale.ROOT);
            if (lower.startsWith("original") || lower.contains("theatrical")) {
                return group.name();
            }
        }
        Group longest = groups.get(0);
        long best = -1;
        for (Group group : groups) {
            long total = group.files().stream().mapToLong(s -> durations.get(s.fileId())).sum();
            if (total > best) {
                best = total;
                longest = group;
            }
        }
        return longest.name();
    }
}
