package gregochr.cinefilm.plex;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexWatchMerge.Change;
import gregochr.cinefilm.plex.PlexWatchMerge.Cut;
import gregochr.cinefilm.plex.PlexWatchMerge.Existing;
import gregochr.cinefilm.plex.PlexWatchMerge.Result;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the Plex import: one short transaction per Plex film, so a failure part-way leaves every
 * finished film complete and the rest untouched. All Plex, TMDB and artwork I/O happens before {@link #apply} is
 * called. Structure comes first (merge and move editions, which clears the persistence context), then the film is
 * reloaded and Plex's data is written to the survivor.
 */
@Service
public class PlexFilmWriter {

    private static final int NAME_MAX = 255;
    private static final int OVERVIEW_MAX = 4000;

    private final FilmRepository films;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final PlaybackStateRepository states;
    private final LibraryIngest ingest;
    private final ArtworkStore artwork;

    /** Creates the writer. */
    public PlexFilmWriter(FilmRepository films, EditionRepository editions, MediaFileRepository files,
                          PlaybackStateRepository states, LibraryIngest ingest, ArtworkStore artwork) {
        this.films = films;
        this.editions = editions;
        this.files = files;
        this.states = states;
        this.ingest = ingest;
        this.artwork = artwork;
    }

    /**
     * What a film already has, so the import skips fetches it does not need.
     *
     * @param needsTmdb true when TMDB's details must be fetched (no film with the id has credits yet)
     * @param hasPoster true when the film already has a stored Plex poster that is on disk
     * @param hasBackdrop true when the film already has a stored Plex backdrop that is on disk
     */
    public record Preflight(boolean needsTmdb, boolean hasPoster, boolean hasBackdrop) {
    }

    /**
     * Everything one film's transaction needs, fetched beforehand.
     *
     * @param plex the Plex film
     * @param mediaFileIds the CineFilm files it pairs with, in the order Plex lists them (never empty)
     * @param movie TMDB's details, or null (not needed, TMDB off, unavailable, or unknown id)
     * @param posterPath the stored poster ({@code /api/art/...}), or null to leave it
     * @param backdropPath the stored backdrop, or null to leave it
     * @param importTime when the import started
     */
    public record FilmWrite(PlexFilm plex, List<Long> mediaFileIds, MovieDetails movie, String posterPath,
                            String backdropPath, Instant importTime) {
    }

    /**
     * What one film's transaction did.
     *
     * @param merged films folded into the survivor
     * @param editionsMoved editions moved to the survivor
     * @param watched editions made watched
     * @param resumePoints editions given a resume position
     * @param keptNewer editions whose own state was newer than Plex's
     */
    public record Applied(int merged, int editionsMoved, int watched, int resumePoints, int keptNewer) {
    }

    /** Looks at what the paired film already holds. */
    @Transactional(readOnly = true)
    public Preflight preflight(List<Long> mediaFileIds, Long tmdbId) {
        Film anchor = files.findById(mediaFileIds.get(0)).orElseThrow().getEdition().getFilm();
        List<Film> sameId = tmdbId == null ? List.of() : films.findByTmdbIdOrderByIdAsc(tmdbId);
        boolean needsTmdb = tmdbId != null && sameId.stream().noneMatch(f -> !f.getCredits().isEmpty());
        // The film the editions will end up in, so a Plex film moved off a shared anchor is judged on its own art.
        Film destination = tmdbId == null ? anchor
            : !sameId.isEmpty() ? sameId.get(0) : anchor.getTmdbId() == null ? anchor : null;
        return new Preflight(needsTmdb, destination != null && artwork.exists(destination.getPosterPath()),
            destination != null && artwork.exists(destination.getBackdropPath()));
    }

    /** Every stored-artwork path a film holds, so unreferenced files can be swept. */
    @Transactional(readOnly = true)
    public List<String> storedArtworkPaths() {
        List<String> paths = new ArrayList<>(films.findStoredPosterPaths());
        paths.addAll(films.findStoredBackdropPaths());
        return paths;
    }

    /** Writes one Plex film into the library in one transaction. */
    @Transactional
    public Applied apply(FilmWrite write) {
        PlexFilm plex = write.plex();
        Map<Long, MediaFile> byId = files.findAllById(write.mediaFileIds()).stream()
            .collect(Collectors.toMap(MediaFile::getId, Function.identity()));
        Set<Long> editionIds = new LinkedHashSet<>();
        for (Long fileId : write.mediaFileIds()) {
            editionIds.add(byId.get(fileId).getEdition().getId());
        }
        Film anchor = byId.get(write.mediaFileIds().get(0)).getEdition().getFilm();

        int merged = 0;
        Long targetId;
        List<Long> toMerge = new ArrayList<>();
        if (plex.tmdbId() == null) {
            targetId = anchor.getId();
        } else {
            List<Film> sameId = films.findByTmdbIdOrderByIdAsc(plex.tmdbId());
            if (!sameId.isEmpty()) {
                targetId = sameId.get(0).getId();
                sameId.stream().skip(1).forEach(f -> toMerge.add(f.getId()));
            } else if (anchor.getTmdbId() == null) {
                targetId = anchor.getId();
            } else {
                targetId = newFilm(plex, write.importTime()).getId();
            }
        }
        for (Long other : toMerge) {
            ingest.merge(other, targetId);
            merged++;
        }
        int moved = 0;
        for (Long editionId : editionIds) {
            Long sourceId = editions.findById(editionId).orElseThrow().getFilm().getId();
            if (!sourceId.equals(targetId)) {
                editions.moveOne(editionId, films.getReferenceById(targetId));
                moved++;
                if (editions.countByFilmId(sourceId) == 0) {
                    ingest.merge(sourceId, targetId);
                }
            }
        }

        Film film = films.findById(targetId).orElseThrow();
        if (plex.tmdbId() != null) {
            film.setTmdbId(plex.tmdbId());
            if (write.movie() != null) {
                // applyMovie sets TMDB's poster and backdrop; artwork already stored from Plex must survive it.
                String poster = film.getPosterPath();
                String backdrop = film.getBackdropPath();
                ingest.applyMovie(film, write.movie());
                if (ArtworkStore.isStored(poster)) {
                    film.setPosterPath(poster);
                }
                if (ArtworkStore.isStored(backdrop)) {
                    film.setBackdropPath(backdrop);
                }
            }
        }
        overlay(film, write);
        List<Edition> paired = film.getEditions().stream().filter(e -> editionIds.contains(e.getId())).toList();
        if (plex.editionTitle() != null) {
            paired.forEach(e -> e.setName(cut(plex.editionTitle(), NAME_MAX)));
        }
        return watching(plex, paired, editionIds.iterator().next(), write.importTime(), merged, moved);
    }

    private Film newFilm(PlexFilm plex, Instant importTime) {
        Film film = new Film();
        film.setTitleAndSort(cut(plex.title() == null ? "Untitled" : plex.title(), NAME_MAX), plex.titleSort());
        film.setAddedAt(plex.addedAt() == null ? importTime : plex.addedAt());
        return films.saveAndFlush(film);
    }

    /** Plex wins: its title, sort title, year, overview, added date and artwork replace TMDB's. */
    private static void overlay(Film film, FilmWrite write) {
        PlexFilm plex = write.plex();
        if (plex.title() != null) {
            film.setTitleAndSort(cut(plex.title(), NAME_MAX), plex.titleSort());
        }
        if (plex.year() != null) {
            film.setYear(plex.year());
        }
        if (plex.summary() != null) {
            film.setOverview(cut(plex.summary(), OVERVIEW_MAX));
        }
        if (plex.addedAt() != null) {
            film.setAddedAt(plex.addedAt());
        }
        if (write.posterPath() != null) {
            film.setPosterPath(write.posterPath());
        }
        if (write.backdropPath() != null) {
            film.setBackdropPath(write.backdropPath());
        }
        film.setMatchStatus(MatchStatus.MATCHED);
        film.setMatchCandidates(null);
    }

    private Applied watching(PlexFilm plex, List<Edition> paired, Long firstEditionId, Instant importTime, int merged,
                             int moved) {
        PlexWatchMerge.Plex facts = new PlexWatchMerge.Plex(plex.viewCount(), plex.viewOffsetMs(),
            plex.lastViewedAt(), plex.updatedAt());
        int watched = 0;
        int resumePoints = 0;
        int keptNewer = 0;
        for (Edition edition : paired) {
            PlaybackState state = states.findById(edition.getId()).orElse(null);
            Existing existing = state == null ? null : new Existing(state.getPositionMs(), state.isWatched(),
                state.getWatchedAt(), state.getLastPlayedAt());
            Result result = PlexWatchMerge.merge(existing, facts, edition.getId().equals(firstEditionId),
                new Cut(edition.getRuntimeMs(), edition.getCreditsMarkerMs()), importTime);
            if (result.change() == Change.KEPT_NEWER) {
                keptNewer++;
            }
            if (result.change() != Change.UPDATED) {
                continue;
            }
            PlaybackState target = state == null ? new PlaybackState(edition.getId()) : state;
            target.setPositionMs(result.positionMs());
            target.setWatched(result.watched());
            target.setWatchedAt(result.watchedAt());
            target.setLastPlayedAt(result.lastPlayedAt());
            states.save(target);
            watched += result.newlyWatched() ? 1 : 0;
            resumePoints += result.newResumePoint() ? 1 : 0;
        }
        return new Applied(merged, moved, watched, resumePoints, keptNewer);
    }

    private static String cut(String text, int max) {
        return text.substring(0, Math.min(text.length(), max));
    }
}
