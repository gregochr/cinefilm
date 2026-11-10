package gregochr.cinefilm.library;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.FieldError;
import gregochr.cinefilm.config.FieldErrorsException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.GroupingChange;
import gregochr.cinefilm.film.GroupingChangeRepository;
import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.EditionDtos.ArrangedEdition;
import gregochr.cinefilm.library.EditionDtos.ArrangedFile;
import gregochr.cinefilm.library.EditionDtos.EditionFileDto;
import gregochr.cinefilm.library.EditionDtos.EditionRefDto;
import gregochr.cinefilm.library.EditionDtos.EditionRequest;
import gregochr.cinefilm.library.EditionDtos.EditionsView;
import gregochr.cinefilm.library.EditionDtos.FileRequest;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import gregochr.cinefilm.library.EditionDtos.SaveResult;
import gregochr.cinefilm.library.EditionDtos.SuggestionDto;
import gregochr.cinefilm.library.EditionSuggester.SuggestFile;
import gregochr.cinefilm.library.EditionSuggester.Suggestion;
import gregochr.cinefilm.library.FileNameParser.ParsedName;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Edit editions: shows a film's files with the suggested arrangement, and saves the arrangement the owner made.
 *
 * <p>A save takes the {@link LibraryLock} (a scan rewrites films too) and answers a 409 when it cannot, then runs in
 * one transaction. It follows the {@link EditionJoin} rules for moving files: bulk updates that keep every file's id
 * (and so its streams and keyframe index), the persistence context reloaded after each, the emptied editions' playback
 * state and then the editions deleted by id, and the remembered choices repointed. An edition the owner keeps (its id
 * is in the request) keeps its resume position and watched state, clamped to its new runtime, whatever it is renamed
 * to; a new edition starts fresh; an edition left with no files goes with its state. Files marked Not this film each
 * become a film of their own that needs a match.
 */
@Service
public class EditionArranger {

    private static final String BUSY = "A scan is running";
    private static final int TITLE_MAX = 255;
    private static final int SUMMARY_MAX = 500;
    private static final String ORIGINAL = "Original";

    private final FilmRepository films;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final PlaybackStateRepository states;
    private final FilmPreferenceRepository preferences;
    private final GroupingChangeRepository changes;
    private final EditionJoin join;
    private final LibraryLock lock;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** Creates the service over the repositories, the join (whose choice-repointing it reuses) and the lock. */
    public EditionArranger(FilmRepository films, EditionRepository editions, MediaFileRepository files,
                           PlaybackStateRepository states, FilmPreferenceRepository preferences,
                           GroupingChangeRepository changes, EditionJoin join, LibraryLock lock,
                           PlatformTransactionManager transactions, Clock clock) {
        this.films = films;
        this.editions = editions;
        this.files = files;
        this.states = states;
        this.preferences = preferences;
        this.changes = changes;
        this.join = join;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /**
     * What the panel loads: every original file of the film, its editions, and the suggested arrangement.
     *
     * @param filmId the film
     * @return the files, editions and suggestion
     * @throws NotFoundException when there is no such film
     */
    @Transactional(readOnly = true)
    public EditionsView view(Long filmId) {
        Film film = films.findById(filmId).orElseThrow(() -> new NotFoundException("No film " + filmId));
        List<Edition> all = film.getEditions();
        Long currentDefault = currentDefault(film);
        List<EditionFileDto> fileDtos = new ArrayList<>();
        List<SuggestFile> suggestFiles = new ArrayList<>();
        for (Edition edition : all) {
            for (MediaFile file : edition.originalParts()) {
                fileDtos.add(new EditionFileDto(file.getId(), fileName(file.getPath()), file.getDurationMs(),
                    file.getWidth(), file.getHeight(), file.getVideoCodec(), file.getSizeBytes(),
                    file.getPresence().name(), edition.getId(), file.getPartNumber()));
                suggestFiles.add(new SuggestFile(file.getId(), file.getPath(), file.getDurationMs(), edition.getId(),
                    edition.getName(), file.getPartNumber()));
            }
        }
        List<EditionRefDto> refs = all.stream()
            .map(e -> new EditionRefDto(e.getId(), e.getName(), e.getId().equals(currentDefault))).toList();
        Suggestion suggestion = EditionSuggester.suggest(film.getTitle(), film.isEditionsConfirmed(), currentDefault,
            suggestFiles);
        List<ArrangedEdition> arranged = suggestion.editions().stream()
            .map(g -> new ArrangedEdition(g.editionId(), g.name(),
                g.files().stream().map(s -> new ArrangedFile(s.fileId(), s.part())).toList())).toList();
        return new EditionsView(fileDtos, refs,
            new SuggestionDto(suggestion.reason(), arranged, suggestion.notThisFilm(), suggestion.defaultName()));
    }

    /**
     * Saves the owner's arrangement of a film's files.
     *
     * @param filmId the film
     * @param request the editions the film ends up with and the files that are not this film
     * @return the film and the new films created for the files marked Not this film
     * @throws ConflictException when a scan, an import or a match holds the library lock
     * @throws NotFoundException when there is no such film
     * @throws FieldErrorsException when the arrangement breaks a rule
     * @throws BadRequestException when the request names an edition that is not the film's, or names one twice
     */
    public SaveResult save(Long filmId, SaveRequest request) {
        return locked(() -> tx.execute(status -> arrange(filmId, request)));
    }

    private SaveResult arrange(Long filmId, SaveRequest request) {
        Film film = films.findById(filmId).orElseThrow(() -> new NotFoundException("No film " + filmId));
        Map<Long, MediaFile> originals = new LinkedHashMap<>();
        Map<Long, List<Long>> filesBefore = new HashMap<>();
        Map<Long, Long> editionOfFile = new HashMap<>();
        Map<Long, Long> timelineMs = new HashMap<>();
        List<StateCarry.Old> olds = new ArrayList<>();
        for (Edition edition : film.getEditions()) {
            List<Long> ids = new ArrayList<>();
            List<Long> durations = new ArrayList<>();
            for (MediaFile file : edition.originalParts()) {
                originals.put(file.getId(), file);
                editionOfFile.put(file.getId(), edition.getId());
                timelineMs.put(file.getId(), file.timelineDurationMs());
                ids.add(file.getId());
                durations.add(file.timelineDurationMs());
            }
            filesBefore.put(edition.getId(), ids);
            olds.add(new StateCarry.Old(ids, durations,
                states.findById(edition.getId()).map(EditionJoin::stateOf).orElse(null),
                edition.getCreditsMarkerMs()));
        }
        List<FieldError> errors = EditionRules.validate(request, originals.keySet());
        if (!errors.isEmpty()) {
            throw new FieldErrorsException(errors);
        }
        List<Long> notThis = request.notThisFilm() == null ? List.of() : request.notThisFilm();
        Set<Long> kept = keptEditionIds(film, request);
        Set<Long> emptied = new LinkedHashSet<>(filesBefore.keySet());
        emptied.removeAll(kept);
        String title = film.getTitle();
        int editionCount = request.editions().size();

        // Everything new is created first: each bulk update below clears the persistence context.
        List<Long> targets = new ArrayList<>();
        for (EditionRequest edition : request.editions()) {
            targets.add(edition.id() != null ? edition.id() : createEdition(film, edition.name().strip()));
        }
        List<Long> newFilmIds = new ArrayList<>();
        List<Long> newEditionIds = new ArrayList<>();
        for (Long fileId : notThis) {
            Film split = createFilm(originals.get(fileId));
            newFilmIds.add(split.getId());
            newEditionIds.add(createEdition(split, ORIGINAL));
        }

        Map<Long, List<Long>> moves = new LinkedHashMap<>();
        Map<Long, Integer> partOf = new HashMap<>();
        Map<Long, Long> targetOfFile = new HashMap<>();
        Map<Long, List<Long>> newFiles = new HashMap<>();
        for (int i = 0; i < editionCount; i++) {
            for (FileRequest file : request.editions().get(i).files()) {
                partOf.put(file.fileId(), file.part() == null ? 1 : file.part());
                targetOfFile.put(file.fileId(), targets.get(i));
                newFiles.computeIfAbsent(targets.get(i), k -> new ArrayList<>()).add(file.fileId());
                if (!targets.get(i).equals(editionOfFile.get(file.fileId()))) {
                    moves.computeIfAbsent(targets.get(i), k -> new ArrayList<>()).add(file.fileId());
                }
            }
        }
        for (int i = 0; i < notThis.size(); i++) {
            partOf.put(notThis.get(i), 1);
            moves.computeIfAbsent(newEditionIds.get(i), k -> new ArrayList<>()).add(notThis.get(i));
        }
        Map<Long, List<StateCarry.Piece>> pieces = StateCarry.split(olds, targetOfFile);
        moves.forEach((target, ids) -> files.moveToEdition(ids, editions.getReferenceById(target)));

        List<Long> touched = new ArrayList<>(targets);
        touched.addAll(newEditionIds);
        for (int i = 0; i < touched.size(); i++) {
            String name = i < editionCount ? request.editions().get(i).name().strip() : ORIGINAL;
            Long id = touched.get(i);
            StateCarry.Result carried = i < editionCount ? StateCarry.land(pieces.getOrDefault(id, List.of()),
                StateCarry.inPlayOrder(partOf, newFiles.get(id)), timelineMs) : null;
            settle(id, name, partOf, filesBefore.get(id), carried);
        }
        for (Long editionId : emptied) {
            dropRenditions(editionId);
        }
        if (!emptied.isEmpty()) {
            states.deleteByEditionIds(emptied);
            editions.deleteByIds(emptied);
        }

        Long defaultId = defaultEditionId(request, targets);
        FilmPreference preference = preferences.findById(filmId).orElseGet(() -> new FilmPreference(filmId));
        preference.setDefaultEditionId(defaultId);
        preference.setDefaultEditionChosen(true);
        if (emptied.contains(preference.getLastEditionId())) {
            preference.setLastEditionId(defaultId);
        }
        preferences.save(preference);
        join.repoint(filmId, defaultId, emptied);

        Film settled = films.findById(filmId).orElseThrow();
        settled.setEditionsConfirmed(true);
        films.save(settled);
        report(title, editionCount, notThis.size());
        return new SaveResult(filmId, newFilmIds);
    }

    /** The edition ids the request keeps; each must be one of this film's editions, and named once. */
    private static Set<Long> keptEditionIds(Film film, SaveRequest request) {
        Set<Long> own = new HashSet<>();
        film.getEditions().forEach(e -> own.add(e.getId()));
        Set<Long> kept = new LinkedHashSet<>();
        for (EditionRequest edition : request.editions()) {
            if (edition.id() == null) {
                continue;
            }
            if (!own.contains(edition.id())) {
                throw new BadRequestException("Edition " + edition.id() + " is not an edition of this film");
            }
            if (!kept.add(edition.id())) {
                throw new BadRequestException("Edition " + edition.id() + " is listed twice");
            }
        }
        return kept;
    }

    private Long createEdition(Film film, String name) {
        Edition edition = new Edition();
        edition.setFilm(film);
        edition.setName(name);
        edition.setYear(film.getYear());
        edition.setGroupingSource(GroupingSource.OWNER);
        return editions.saveAndFlush(edition).getId();
    }

    /** A film of its own for a file that is not this film: titled from the file name, unmatched, for Fix match. */
    private Film createFilm(MediaFile file) {
        ParsedName parsed = FileNameParser.parse(file.getPath());
        // The parser always carries a title (as in LibraryIngest).
        String title = parsed.title();
        Film film = new Film();
        film.setTitleAndSort(title.substring(0, Math.min(title.length(), TITLE_MAX)));
        film.setYear(parsed.year());
        film.setAddedAt(file.getAddedAt());
        film.setMatchStatus(MatchStatus.NEEDS_MATCH);
        film.setMatchCandidates("[]");
        return films.saveAndFlush(film);
    }

    /**
     * Names the edition, numbers its parts and recomputes its runtime. Its state and credits marker are the ones
     * carried from the old editions ({@link StateCarry}); when a file has no known duration nothing can be carried, so
     * the resume position is only clamped to the new runtime and the marker is cleared if the files changed.
     */
    private void settle(Long editionId, String name, Map<Long, Integer> partOf, List<Long> before,
                        StateCarry.Result carried) {
        Edition edition = editions.findById(editionId).orElseThrow();
        edition.setName(name);
        edition.setGroupingSource(GroupingSource.OWNER);
        for (MediaFile file : edition.getMediaFiles()) {
            Integer part = partOf.get(file.getId());
            if (part != null) {
                file.setPartNumber(part);
            }
        }
        edition.recomputeRuntime();
        if (carried != null) {
            edition.setCreditsMarkerMs(carried.markerMs());
            if (carried.state() == null) {
                states.deleteById(editionId);
            } else {
                states.save(EditionJoin.apply(states.findById(editionId)
                    .orElseGet(() -> new PlaybackState(editionId)), carried.state()));
            }
        } else {
            List<Long> after = edition.originalParts().stream().map(MediaFile::getId).toList();
            if (!after.equals(before == null ? List.of() : before)) {
                edition.setCreditsMarkerMs(null);
            }
            long runtime = edition.getRuntimeMs();
            states.findById(editionId).ifPresent(state -> clamp(state, runtime));
        }
        editions.save(edition);
    }

    private void clamp(PlaybackState state, long runtimeMs) {
        if (runtimeMs > 0 && state.getPositionMs() > runtimeMs) {
            state.setPositionMs(runtimeMs);
            states.save(state);
        }
    }

    /** An emptied edition may still hold pre-made renditions of its old file: they go before it does. */
    private void dropRenditions(Long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow();
        if (!edition.getMediaFiles().isEmpty()) {
            edition.getMediaFiles().clear();
            editions.saveAndFlush(edition);
        }
    }

    private static Long defaultEditionId(SaveRequest request, List<Long> targets) {
        EditionRequest chosen = request.editions().stream().filter(EditionRequest::isDefault).findFirst()
            .orElseThrow(() -> new IllegalStateException("Validation guarantees one default edition"));
        return targets.get(request.editions().indexOf(chosen));
    }

    private void report(String title, int editionCount, int movedOut) {
        String editionsText = editionCount + (editionCount == 1 ? " edition" : " editions");
        String summary = movedOut == 0
            ? "You arranged \"" + title + "\" into " + editionsText + " with Edit editions"
            : "You moved " + movedOut + (movedOut == 1 ? " file" : " files") + " out of \"" + title
                + "\" and arranged it into " + editionsText + " with Edit editions";
        changes.save(new GroupingChange(clock.instant(), movedOut == 0 ? GroupingChange.JOIN : GroupingChange.SPLIT,
            summary.substring(0, Math.min(summary.length(), SUMMARY_MAX))));
    }

    /** The edition the film opens on: the stored default while it is there, else the scan's pick. */
    private Long currentDefault(Film film) {
        Long stored = preferences.findById(film.getId()).map(FilmPreference::getDefaultEditionId).orElse(null);
        if (stored != null && film.getEditions().stream().anyMatch(e -> e.getId().equals(stored))) {
            return stored;
        }
        return DefaultEdition.choose(film.getEditions()).map(Edition::getId).orElse(null);
    }

    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    private <T> T locked(Supplier<T> work) {
        if (!lock.tryAcquire(LibraryLock.EDIT)) {
            throw new ConflictException(BUSY);
        }
        try {
            return work.get();
        } finally {
            lock.release();
        }
    }
}
