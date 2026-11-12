package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.NeedRow;
import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.SpokenLanguages;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.subtitle.ForcedNeed.Decision;
import gregochr.cinefilm.subtitle.ForcedNeed.Languages;
import gregochr.cinefilm.subtitle.ForcedNeed.Need;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.CountsDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin's view of which films need forced subtitles, and the owner's say on it. The need is worked out when read
 * ({@link ForcedNeed}) from stored inputs, never stored itself, so attaching or moving a subtitle file changes it at
 * once. The list is built from three queries, never one per film.
 */
@Service
public class ForcedNeedService {

    private final FilmRepository films;
    private final SubtitleSidecarRepository sidecars;
    private final MediaFileRepository files;
    private final EditionRepository editions;
    private final ForcedSubtitleService forcedSubtitles;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public ForcedNeedService(FilmRepository films, SubtitleSidecarRepository sidecars, MediaFileRepository files,
                             EditionRepository editions, ForcedSubtitleService forcedSubtitles, LibraryLock lock,
                             PlatformTransactionManager transactions) {
        this.films = films;
        this.sidecars = sidecars;
        this.files = files;
        this.editions = editions;
        this.forcedSubtitles = forcedSubtitles;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Every film by sort title, with its need and the counts. */
    @Transactional(readOnly = true)
    public NeedListDto list() {
        List<NeedRow> rows = films.findNeedRows();
        Set<Long> withSidecar = new HashSet<>(sidecars.findFilmIdsWithPresentSidecar());
        Set<Long> withEmbedded = new HashSet<>(files.findFilmIdsWithEmbeddedForced());
        // Only the films with sidecar evidence (a couple of dozen) are asked what playback would serve.
        Set<Long> served = withSidecar.isEmpty() ? Set.of() : servedFilmIds(withSidecar);
        List<FilmNeedDto> dtos = new ArrayList<>();
        for (NeedRow row : rows) {
            dtos.add(dto(row, withSidecar.contains(row.id()), served.contains(row.id()),
                withEmbedded.contains(row.id())));
        }
        return new NeedListDto(dtos, counts(dtos));
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
    public FilmNeedDto choose(Long filmId, ChoiceRequest request) {
        ForcedNeedChoice choice = parse(request);
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            throw new ConflictException("A scan is running");
        }
        try {
            return tx.execute(status -> chooseLocked(filmId, choice));
        } finally {
            lock.release();
        }
    }

    private FilmNeedDto chooseLocked(Long filmId, ForcedNeedChoice choice) {
        Film film = films.findByIdForUpdate(filmId).orElseThrow(() -> new NotFoundException("No such film"));
        film.setForcedNeedOwner(choice);
        NeedRow row = new NeedRow(film.getId(), film.getTitle(), film.getYear(), film.getTmdbId(),
            film.getOriginalLanguage(), film.getSpokenLanguages(), film.getLanguagesFetchedAt(), choice);
        boolean hasSidecar = sidecars.countPresentForFilm(filmId) > 0;
        boolean served = hasSidecar && !servedFilmIds(List.of(filmId)).isEmpty();
        return dto(row, hasSidecar, served, files.countEmbeddedForcedForFilm(filmId) > 0);
    }

    /**
     * The films among these with an edition that playback would serve a forced subtitle for: the same code playback
     * uses decides ({@link ForcedSubtitleService#forEditions}), so an empty file or a conflict is not counted. Their
     * editions and files come in one fetch and playback's own sidecar query runs once, so the cost does not grow with
     * the number of films.
     */
    private Set<Long> servedFilmIds(Collection<Long> filmIds) {
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(filmIds);
        Set<Long> servedEditions = forcedSubtitles.forEditions(fetched).keySet();
        Set<Long> served = new HashSet<>();
        for (Edition edition : fetched) {
            if (servedEditions.contains(edition.getId())) {
                served.add(edition.getFilm().getId());
            }
        }
        return served;
    }

    private static ForcedNeedChoice parse(ChoiceRequest request) {
        if (request == null || request.choice() == null) {
            return null;
        }
        try {
            return ForcedNeedChoice.valueOf(request.choice());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("The choice must be NEEDED, NOT_NEEDED or null");
        }
    }

    private static FilmNeedDto dto(NeedRow row, boolean hasSidecar, boolean served, boolean hasEmbeddedForced) {
        List<String> spoken = SpokenLanguages.parse(row.spokenLanguages());
        // TMDB counts as asked once stamped and while the film still has an id; a film it no longer has has no
        // languages at all.
        boolean known = row.tmdbId() != null && row.languagesFetchedAt() != null
            && (row.originalLanguage() != null || row.spokenLanguages() != null);
        Languages languages = known ? new Languages(row.originalLanguage(), spoken) : null;
        // No OpenSubtitles evidence exists yet (phase 2c): null means it has not been asked.
        Decision decision = ForcedNeed.decide(row.owner(), hasSidecar, hasEmbeddedForced, null, languages);
        return new FilmNeedDto(row.id(), row.title(), row.year(), decision.need().name(), decision.reason().name(),
            row.owner() == null ? null : row.owner().name(), row.originalLanguage(), spoken, served);
    }

    private static CountsDto counts(List<FilmNeedDto> rows) {
        int needed = 0;
        int neededMissing = 0;
        int unsure = 0;
        int unknown = 0;
        int notNeeded = 0;
        for (FilmNeedDto row : rows) {
            switch (Need.valueOf(row.need())) {
                case NEEDED -> {
                    needed++;
                    if (!row.hasForcedSubtitle()) {
                        neededMissing++;
                    }
                }
                case UNSURE -> unsure++;
                case UNKNOWN -> unknown++;
                case NOT_NEEDED -> notNeeded++;
            }
        }
        return new CountsDto(needed, neededMissing, unsure, unknown, notNeeded);
    }
}
