package gregochr.cinefilm.library;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.PartsMatch;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.Attention;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.CandidateDto;
import gregochr.cinefilm.library.AdminDtos.CountsDto;
import gregochr.cinefilm.library.AdminDtos.GroupingChangeDto;
import gregochr.cinefilm.library.AdminDtos.GroupingReport;
import gregochr.cinefilm.library.AdminDtos.LastScanDto;
import gregochr.cinefilm.library.AdminDtos.ProgressDto;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import gregochr.cinefilm.library.FileNameParser.ParsedName;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRefresher;
import gregochr.cinefilm.plex.RuntimeSignal;
import gregochr.cinefilm.ratings.RatingsRefresher;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** What the Admin panels show: the scan status and the films needing attention. */
@Service
public class AdminService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ScanService scans;
    private final ScanRunRepository runs;
    private final FilmRepository films;
    private final MediaFileRepository files;
    private final LibraryRoot root;
    private final RatingsRefresher ratings;
    private final OpenSubtitlesRefresher subtitleSearch;
    private final GroupingChangeRepository changes;

    /**
     * Creates the service over the scan service, the repositories, the library root and the ratings and subtitle
     * search refreshers.
     */
    public AdminService(ScanService scans, ScanRunRepository runs, FilmRepository films,
                        MediaFileRepository files, LibraryRoot root, RatingsRefresher ratings,
                        OpenSubtitlesRefresher subtitleSearch, GroupingChangeRepository changes) {
        this.ratings = ratings;
        this.subtitleSearch = subtitleSearch;
        this.changes = changes;
        this.scans = scans;
        this.runs = runs;
        this.films = films;
        this.files = files;
        this.root = root;
    }

    /** The Library scan panel: folder, progress, last scan and counts. */
    @Transactional(readOnly = true)
    public ScanStatusDto status() {
        ScanService.Progress progress = scans.progress();
        LastScanDto last = runs.findFirstByFinishedAtNotNullOrderByIdDesc()
            .map(r -> new LastScanDto(r.getStartedAt(), r.getFinishedAt(), r.getTrigger(), r.getFilesSeen(),
                r.getNewFiles(), r.getMissingFiles(), r.getError()))
            .orElse(null);
        long browsable = films.findAll().stream().filter(Film::browsable).count();
        CountsDto counts = new CountsDto(browsable, last == null ? 0 : last.newFiles(),
            files.countOriginals(Presence.MISSING), files.countPresent(Playability.PROTECTED),
            files.countPresent(Playability.NEEDS_CONVERSION),
            films.findByMatchStatusOrderBySortTitle(MatchStatus.NEEDS_MATCH).size(),
            files.countPresent(Playability.UNREADABLE));
        return new ScanStatusDto(scans.isRunning(), root.root().toString(),
            progress == null ? null : new ProgressDto(progress.processed(), progress.total(), progress.current()),
            last, counts, films.findLatestRatingsFetchedAt(), ratings.isRunning(), ratings.enabled(),
            films.findLatestOpenSubtitlesSearchedAt(), subtitleSearch.isRunning(), subtitleSearch.enabled());
    }

    /**
     * Films needing attention: unmatched, protected, needs conversion, unreadable, several files that are genuinely
     * ambiguous, and parts that do not match.
     */
    @Transactional(readOnly = true)
    public Attention attention() {
        List<AttentionItem> items = new ArrayList<>();
        for (Film film : films.findByMatchStatusOrderBySortTitle(MatchStatus.NEEDS_MATCH)) {
            List<CandidateDto> candidates = candidates(film);
            String detail = candidates.isEmpty() ? "No match found on TMDB"
                : candidates.size() + (candidates.size() == 1 ? " possible match" : " possible matches");
            items.add(item("needs-match", film, detail, fileNames(film), candidates));
        }
        addFiles(items, Playability.PROTECTED, "protected", "Protected by FairPlay, so it can't be played");
        addFiles(items, Playability.NEEDS_CONVERSION, "needs-conversion",
            "Needs converting to MP4 before it can be played");
        addFiles(items, Playability.UNREADABLE, "unreadable",
            "ffprobe could not read this file; it is tried again at the next scan");
        List<Film> all = films.findAll();
        for (Film film : all) {
            String why = film.isEditionsConfirmed() ? null : ambiguity(film);
            if (why != null) {
                items.add(item("multiple-files", film, why, fileNames(film), List.of()));
            }
        }
        for (Film film : all) {
            for (Edition edition : film.getEditions()) {
                addPartsMismatch(items, film, edition);
            }
        }
        return new Attention(items);
    }

    /** The newest 20 lines of the grouping report, newest first. */
    @Transactional(readOnly = true)
    public GroupingReport groupingReport() {
        return new GroupingReport(changes.findTop20ByOrderByHappenedAtDescIdDesc().stream()
            .map(c -> new GroupingChangeDto(c.getHappenedAt(), c.getKind(), c.getSummary())).toList());
    }

    /** Parts of one edition that cannot be joined without re-encoding play by transcoding: say so. */
    private static void addPartsMismatch(List<AttentionItem> items, Film film, Edition edition) {
        List<MediaFile> parts = edition.originalParts().stream()
            .filter(p -> p.getPresence() == Presence.PRESENT).toList();
        if (parts.size() < 2) {
            return;
        }
        PartsMatch.Result result = PartsMatch.check(parts);
        if (result.matches()) {
            return;
        }
        int more = result.differences().size() - 1;
        String detail = result.differences().get(0) + (more > 0 ? " (and " + more + " more)" : "");
        items.add(item("parts-mismatch", film, detail, parts.stream().map(p -> name(p.getPath())).toList(),
            List.of()));
    }

    /**
     * Why a film the owner has not settled has files that cannot be told apart, or null when it is fine. Only genuine
     * ambiguity counts: a film with differently tagged cuts (Gladiator) or properly numbered parts (Two Towers) is not
     * listed. Several reasons are joined into one line.
     */
    private static String ambiguity(Film film) {
        List<MediaFile> originals = new ArrayList<>();
        for (Edition edition : film.getEditions()) {
            edition.originalParts().stream().filter(f -> f.getPresence() == Presence.PRESENT)
                .forEach(originals::add);
        }
        Map<Long, ParsedName> parsed = new LinkedHashMap<>();
        originals.forEach(f -> parsed.put(f.getId(), FileNameParser.parse(f.getPath())));
        List<String> reasons = new ArrayList<>();
        Set<String> titles = new LinkedHashSet<>();
        parsed.values().forEach(p -> titles.add(p.comparisonTitle()));
        if (titles.size() > 1) {
            reasons.add("Files look like different films");
        }
        if (parsed.values().stream().anyMatch(ParsedName::tagConflict)) {
            reasons.add("The edition tags in a file name and its folder disagree");
        }
        reasons.addAll(partProblems(parsed.values()));
        String untagged = untaggedCopies(film, parsed);
        if (untagged != null) {
            reasons.add(untagged);
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    /** Duplicate part numbers and gaps among the part-numbered files that share an edition tag. */
    private static List<String> partProblems(Iterable<ParsedName> parsed) {
        Map<String, List<Integer>> byTag = new LinkedHashMap<>();
        for (ParsedName p : parsed) {
            if (p.partTag() != null) {
                byTag.computeIfAbsent(p.editionTag() == null ? "" : p.editionTag().toLowerCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(p.partTag());
            }
        }
        Set<Integer> duplicates = new TreeSet<>();
        Set<Integer> missing = new TreeSet<>();
        Set<Integer> found = new TreeSet<>();
        for (List<Integer> numbers : byTag.values()) {
            Set<Integer> seen = new TreeSet<>();
            for (Integer number : numbers) {
                if (!seen.add(number)) {
                    duplicates.add(number);
                }
            }
            boolean gap = false;
            for (int n = 1; n <= seen.stream().max(Comparator.naturalOrder()).orElse(0); n++) {
                if (!seen.contains(n)) {
                    missing.add(n);
                    gap = true;
                }
            }
            if (gap) {
                found.addAll(seen);
            }
        }
        List<String> problems = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            problems.add("Duplicate part numbers: " + join(duplicates));
        }
        if (!missing.isEmpty()) {
            problems.add("Parts found: " + join(found) + ", missing: " + join(missing));
        }
        return problems;
    }

    /**
     * Two or more editions with no edition tag, no plain edition word and no part number: nothing says which is which.
     * States the runtime difference when it is over 15%.
     */
    private static String untaggedCopies(Film film, Map<Long, ParsedName> parsed) {
        List<Long> runtimes = new ArrayList<>();
        int count = 0;
        for (Edition edition : film.getEditions()) {
            List<ParsedName> names = edition.originalParts().stream().map(f -> parsed.get(f.getId()))
                .filter(Objects::nonNull).toList();
            if (!names.isEmpty() && names.stream().allMatch(p -> p.editionTag() == null && p.plainEdition() == null
                && p.partTag() == null)) {
                count++;
                if (edition.getRuntimeMs() > 0) {
                    runtimes.add(edition.getRuntimeMs());
                }
            }
        }
        if (count < 2) {
            return null;
        }
        String line = count + " untagged versions of one film";
        if (runtimes.size() > 1) {
            long shortest = runtimes.stream().min(Comparator.naturalOrder()).orElseThrow();
            long longest = runtimes.stream().max(Comparator.naturalOrder()).orElseThrow();
            if (RuntimeSignal.differs(shortest, longest)) {
                line += ", runtimes differ by " + RuntimeSignal.percent(shortest, longest) + "%";
            }
        }
        return line;
    }

    private static String join(Set<Integer> numbers) {
        return String.join(", ", numbers.stream().map(String::valueOf).toList());
    }

    private void addFiles(List<AttentionItem> items, Playability playability, String kind, String detail) {
        for (MediaFile file : files.findOriginals(Presence.PRESENT, playability)) {
            Film film = file.getEdition().getFilm();
            items.add(item(kind, film, detail, List.of(name(file.getPath())), List.of()));
        }
    }

    private static AttentionItem item(String kind, Film film, String detail, List<String> names,
                                      List<CandidateDto> candidates) {
        return new AttentionItem(kind, film.getId(), film.getTitle(), film.getYear(),
            detail, names, candidates);
    }

    /** Names of the film's original files (every part) that are on disk. */
    private static List<String> fileNames(Film film) {
        List<String> names = new ArrayList<>();
        for (Edition edition : film.getEditions()) {
            for (MediaFile part : edition.originalParts()) {
                if (part.getPresence() == Presence.PRESENT) {
                    names.add(name(part.getPath()));
                }
            }
        }
        return names;
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static List<CandidateDto> candidates(Film film) {
        if (film.getMatchCandidates() == null) {
            return List.of();
        }
        try {
            List<Candidate> stored = JSON.readValue(film.getMatchCandidates(), new TypeReference<>() { });
            return stored.stream().map(c -> new CandidateDto(c.tmdbId(), c.title(), c.year(), c.runtimeMinutes(),
                c.posterPath())).toList();
        } catch (JacksonException e) {
            return List.of();
        }
    }
}
