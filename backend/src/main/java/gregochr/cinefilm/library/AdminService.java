package gregochr.cinefilm.library;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.AdminDtos.Attention;
import gregochr.cinefilm.library.AdminDtos.AttentionItem;
import gregochr.cinefilm.library.AdminDtos.CandidateDto;
import gregochr.cinefilm.library.AdminDtos.CountsDto;
import gregochr.cinefilm.library.AdminDtos.LastScanDto;
import gregochr.cinefilm.library.AdminDtos.ProgressDto;
import gregochr.cinefilm.library.AdminDtos.ScanStatusDto;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import java.util.ArrayList;
import java.util.List;
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

    /** Creates the service over the scan service, the repositories and the library root. */
    public AdminService(ScanService scans, ScanRunRepository runs, FilmRepository films,
                        MediaFileRepository files, LibraryRoot root) {
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
            last, counts);
    }

    /** Films needing attention: no match, protected, needs conversion, unreadable, several files for one film. */
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
        for (Film film : films.findAll()) {
            List<String> names = fileNames(film);
            if (names.size() > 1) {
                items.add(item("multiple-files", film, names.size() + " files match one film", names, List.of()));
            }
        }
        return new Attention(items);
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

    /** Names of the film's original files that are on disk and playable. */
    private static List<String> fileNames(Film film) {
        List<String> names = new ArrayList<>();
        for (Edition edition : film.getEditions()) {
            MediaFile original = edition.originalFile();
            if (original != null && original.getPresence() == Presence.PRESENT) {
                names.add(name(original.getPath()));
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
