package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.FullNeedRow;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.AudioRow;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.film.SpokenLanguages;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.ForcedNeed.Languages;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.FullNeed.Decision;
import gregochr.cinefilm.subtitle.FullNeed.Need;
import gregochr.cinefilm.subtitle.FullNeed.Track;
import gregochr.cinefilm.subtitle.FullNeedDtos.EditionAudioDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedCountsDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin's view of which films need full English subtitles, and the owner's say on it. The need is worked out when read
 * ({@link FullNeed}) from TMDB's languages and the audio of each present edition, never stored itself. The list is
 * built from two queries, never one per film.
 */
@Service
public class FullNeedService {

    private static final String ENGLISH = "en";
    private static final String NO_LANGUAGE = "xx";

    private final FilmRepository films;
    private final MediaFileRepository files;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public FullNeedService(FilmRepository films, MediaFileRepository files, LibraryLock lock,
                           PlatformTransactionManager transactions) {
        this.films = films;
        this.files = files;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** One present edition's audio: its name and the label of part 1's default (else first) audio track. */
    private record EditionAudio(String name, String label) {
    }

    /**
     * The films in scope (TMDB's original language is not English, {@code xx} or missing, or the owner chose) by sort
     * title, with their need and the counts.
     */
    @Transactional(readOnly = true)
    public FullNeedListDto list() {
        List<FullNeedRow> inScope = films.findFullNeedRows().stream().filter(FullNeedService::inScope).toList();
        Map<Long, List<EditionAudio>> audio = inScope.isEmpty() ? Map.of()
            : editionAudio(files.findAudioRows(inScope.stream().map(FullNeedRow::id).toList()));
        List<FullNeedFilmDto> dtos = new ArrayList<>();
        for (FullNeedRow row : inScope) {
            dtos.add(dto(row, audio.getOrDefault(row.id(), List.of())));
        }
        return new FullNeedListDto(dtos, counts(dtos));
    }

    /**
     * Sets or clears the owner's choice for a film. A scan rewrites films, so the change is refused (409) while one
     * runs, as the other Admin changes are. The lock is taken outside the transaction and given back only after it
     * has committed.
     *
     * @param filmId the film
     * @param request the choice: {@code NEEDED}, {@code NOT_NEEDED}, or null for automatic
     * @return the film's row afterwards
     * @throws ConflictException when a scan or another change holds the library lock
     * @throws NotFoundException when there is no such film
     * @throws BadRequestException when the choice is any other value
     */
    public FullNeedFilmDto choose(Long filmId, ChoiceRequest request) {
        FullNeedChoice choice = parse(request);
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            throw new ConflictException("A scan is running");
        }
        try {
            return tx.execute(status -> chooseLocked(filmId, choice));
        } finally {
            lock.release();
        }
    }

    /**
     * The need of one film, as {@link #list} would say it (the film's detail page uses it): one extra query for the
     * film's audio.
     *
     * @param film the film, with its stored languages and owner's choice
     * @return the need value: {@code NEEDED}, {@code NOT_NEEDED}, {@code UNSURE} or {@code UNKNOWN}
     */
    @Transactional(readOnly = true)
    public String needOf(Film film) {
        FullNeedRow row = row(film, film.getFullNeedOwner());
        return decide(row, inScope(row) ? editionAudio(files.findAudioRows(List.of(film.getId())))
            .getOrDefault(film.getId(), List.of()) : List.of()).need().name();
    }

    private FullNeedFilmDto chooseLocked(Long filmId, FullNeedChoice choice) {
        Film film = films.findByIdForUpdate(filmId).orElseThrow(() -> new NotFoundException("No such film"));
        film.setFullNeedOwner(choice);
        FullNeedRow row = row(film, choice);
        return dto(row, editionAudio(files.findAudioRows(List.of(filmId))).getOrDefault(filmId, List.of()));
    }

    private static FullNeedRow row(Film film, FullNeedChoice choice) {
        return new FullNeedRow(film.getId(), film.getTitle(), film.getYear(), film.getTmdbId(),
            film.getOriginalLanguage(), film.getSpokenLanguages(), film.getLanguagesFetchedAt(), choice);
    }

    /** A film is in scope when the owner chose for it, or TMDB says it was made in a language other than English. */
    private static boolean inScope(FullNeedRow row) {
        if (row.owner() != null) {
            return true;
        }
        String original = SpokenLanguages.code(row.originalLanguage());
        return original != null && !ENGLISH.equals(original) && !NO_LANGUAGE.equals(original);
    }

    /**
     * Each film's present editions with their audio label, from the audio rows (ordered by edition, part, track). An
     * edition counts when its part 1 (its first original file) is present; its label is that file's default audio
     * track's, else its first track's.
     */
    private static Map<Long, List<EditionAudio>> editionAudio(List<AudioRow> rows) {
        Map<Long, List<EditionAudio>> byFilm = new LinkedHashMap<>();
        Long edition = null;
        Long part1 = null;
        List<Track> tracks = new ArrayList<>();
        AudioRow first = null;
        for (AudioRow row : rows) {
            if (!row.editionId().equals(edition)) {
                flush(byFilm, first, tracks);
                edition = row.editionId();
                part1 = row.fileId();
                first = row;
                tracks = new ArrayList<>();
            }
            if (row.fileId().equals(part1) && row.isDefault() != null) {
                tracks.add(new Track(row.language(), row.isDefault()));
            }
        }
        flush(byFilm, first, tracks);
        return byFilm;
    }

    private static void flush(Map<Long, List<EditionAudio>> byFilm, AudioRow part1, List<Track> tracks) {
        if (part1 != null && part1.presence() == Presence.PRESENT) {
            byFilm.computeIfAbsent(part1.filmId(), id -> new ArrayList<>())
                .add(new EditionAudio(part1.editionName(), FullNeed.audioLanguage(tracks)));
        }
    }

    private static FullNeedChoice parse(ChoiceRequest request) {
        if (request == null || request.choice() == null) {
            return null;
        }
        try {
            return FullNeedChoice.valueOf(request.choice());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The choice must be NEEDED, NOT_NEEDED or null");
        }
    }

    private static Decision decide(FullNeedRow row, List<EditionAudio> editions) {
        // TMDB counts as asked once stamped and while the film still has an id; a film it no longer has has no
        // languages at all (the same test as forced need).
        boolean known = row.tmdbId() != null && row.languagesFetchedAt() != null
            && (row.originalLanguage() != null || row.spokenLanguages() != null);
        Languages languages = known
            ? new Languages(row.originalLanguage(), SpokenLanguages.parse(row.spokenLanguages())) : null;
        return FullNeed.decide(row.owner(), languages, editions.stream().map(EditionAudio::label).toList());
    }

    private static FullNeedFilmDto dto(FullNeedRow row, List<EditionAudio> editions) {
        Decision decision = decide(row, editions);
        return new FullNeedFilmDto(row.id(), row.title(), row.year(), decision.need().name(),
            decision.reason().name(), row.owner() == null ? null : row.owner().name(),
            editions.stream().map(e -> new EditionAudioDto(e.name(), e.label())).toList());
    }

    private static FullNeedCountsDto counts(List<FullNeedFilmDto> rows) {
        return new FullNeedCountsDto(count(rows, Need.NEEDED), count(rows, Need.UNSURE), count(rows, Need.UNKNOWN),
            count(rows, Need.NOT_NEEDED));
    }

    private static int count(List<FullNeedFilmDto> rows, Need need) {
        return (int) rows.stream().filter(row -> row.need().equals(need.name())).count();
    }
}
