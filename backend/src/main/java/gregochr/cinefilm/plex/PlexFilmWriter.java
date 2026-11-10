package gregochr.cinefilm.plex;

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
import gregochr.cinefilm.library.EditionJoin;
import gregochr.cinefilm.library.GroupingGuard;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final int EDITION_NAME_MAX = 32;
    private static final int OVERVIEW_MAX = 4000;
    private static final int SUMMARY_MAX = 500;

    private final FilmRepository films;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final PlaybackStateRepository states;
    private final LibraryIngest ingest;
    private final ArtworkStore artwork;
    private final EditionJoin join;
    private final GroupingChangeRepository changes;
    private final FilmPreferenceRepository preferences;

    /** Creates the writer; the join, the report lines and the remembered choices serve the grouping to Plex's order. */
    public PlexFilmWriter(FilmRepository films, EditionRepository editions, MediaFileRepository files,
                          PlaybackStateRepository states, LibraryIngest ingest, ArtworkStore artwork,
                          EditionJoin join, GroupingChangeRepository changes, FilmPreferenceRepository preferences) {
        this.films = films;
        this.editions = editions;
        this.files = files;
        this.states = states;
        this.ingest = ingest;
        this.artwork = artwork;
        this.join = join;
        this.changes = changes;
        this.preferences = preferences;
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
     * One version ("Media") of a Plex film, as far as CineFilm has its files.
     *
     * @param fileIds the paired files, in the order Plex lists the Parts (never empty)
     * @param partCount how many Parts Plex lists for the version (more than {@code fileIds} when some are not paired)
     */
    public record MediaWrite(List<Long> fileIds, int partCount) {

        /** True when every Part Plex lists is paired. */
        boolean complete() {
            return fileIds.size() == partCount;
        }
    }

    /**
     * Everything one film's transaction needs, fetched beforehand.
     *
     * @param plex the Plex film
     * @param media the film's versions with the CineFilm files they pair with, in Plex's order (never empty)
     * @param movie TMDB's details, or null (not needed, TMDB off, unavailable, or unknown id)
     * @param posterPath the stored poster ({@code /api/art/...}), or null to leave it
     * @param backdropPath the stored backdrop, or null to leave it
     * @param importTime when the import started
     */
    public record FilmWrite(PlexFilm plex, List<MediaWrite> media, MovieDetails movie, String posterPath,
                            String backdropPath, Instant importTime) {

        /** A write whose every file is a version of its own (one single-file Media each). */
        public static FilmWrite ofFiles(PlexFilm plex, List<Long> fileIds, MovieDetails movie, String posterPath,
                                        String backdropPath, Instant importTime) {
            return new FilmWrite(plex, fileIds.stream().map(id -> new MediaWrite(List.of(id), 1)).toList(), movie,
                posterPath, backdropPath, importTime);
        }

        /** Every paired file, in Plex's order. */
        public List<Long> mediaFileIds() {
            return media.stream().flatMap(m -> m.fileIds().stream()).toList();
        }
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

    /** Looks at what the film the files will end up in already holds (chosen as {@code apply} chooses it). */
    @Transactional(readOnly = true)
    public Preflight preflight(List<Long> mediaFileIds, Long tmdbId) {
        Film anchor = files.findById(mediaFileIds.get(0)).orElseThrow().getEdition().getFilm();
        Map<Long, MediaFile> byId = load(mediaFileIds);
        List<Film> sameId = tmdbId == null ? List.of() : films.findByTmdbIdOrderByIdAsc(tmdbId);
        // The film the editions will end up in (merging deletes the other films' credits), or none: a new film.
        Film destination = destination(tmdbId, anchor, sameId, mediaFileIds, byId);
        boolean needsTmdb = tmdbId != null && (destination == null || destination.getCredits().isEmpty());
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

    /**
     * Writes grouping report lines the import found while pairing (files the guard kept out of a Plex film). A line
     * is written once, however many imports find it.
     */
    @Transactional
    public void flag(List<String> summaries, Instant at) {
        summaries.forEach(summary -> flag(summary, at));
    }

    /**
     * Writes one Plex film into the library in one transaction. Structure comes first, in this order (every bulk
     * statement clears the persistence context, so entities are reloaded after each step): same-TMDB films merge
     * through the guard, an edition that holds files Plex puts elsewhere is unfolded, the paired editions move into the
     * film, each version whose every file is paired is joined (in Plex's order), then Plex's data is written.
     */
    @Transactional
    public Applied apply(FilmWrite write) {
        PlexFilm plex = write.plex();
        Instant at = write.importTime();
        String title = plex.title() == null ? "this film" : plex.title();
        Map<Long, MediaFile> loaded = load(write.mediaFileIds());
        List<MediaWrite> media = write.media();
        flagRuntimeDifferences(write, loaded);
        List<Long> ids = media.stream().flatMap(m -> m.fileIds().stream()).toList();
        Film anchor = loaded.get(ids.get(0)).getEdition().getFilm();

        List<Film> sameId = plex.tmdbId() == null ? List.of() : films.findByTmdbIdOrderByIdAsc(plex.tmdbId());
        Film destination = destination(plex.tmdbId(), anchor, sameId, ids, loaded);
        Long targetId = destination != null ? destination.getId() : newFilm(plex, at).getId();
        if (destination != null && !destination.getId().equals(anchor.getId())) {
            flagRuntimeConflict(destination, ids, loaded, at);
        }
        int merged = mergeSameTmdb(sameId, targetId, at);

        Map<Long, MediaFile> byId = load(ids);
        boolean settled = films.findById(targetId).orElseThrow().isEditionsConfirmed();
        if (!settled && unfoldDisagreements(media, ids, byId, title, at)) {
            byId.putAll(load(ids));
        }
        int moved = moveEditions(editionIdsInOrder(ids, byId), targetId);
        if (!settled) {
            joinVersions(media, ids, title, at);
        }

        Map<Long, MediaFile> after = load(ids);
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
        Set<Long> editionIds = editionIdsInOrder(ids, after);
        List<Long> versionEditions = media.stream().map(m -> after.get(m.fileIds().get(0)).getEdition().getId())
            .distinct().toList();
        List<Edition> paired = film.getEditions().stream().filter(e -> editionIds.contains(e.getId())).toList();
        if (!settled) {
            nameVersions(film, plex, versionEditions);
            chooseDefault(film, versionEditions.get(0));
            film.getEditions().stream()
                .filter(e -> editionIds.contains(e.getId()) && e.getGroupingSource() != GroupingSource.OWNER)
                .forEach(e -> e.setGroupingSource(GroupingSource.PLEX));
        }
        return watching(plex, paired, versionEditions.get(0), at, merged, moved);
    }

    private Map<Long, MediaFile> load(List<Long> ids) {
        return new LinkedHashMap<>(files.findAllById(ids).stream()
            .collect(Collectors.toMap(MediaFile::getId, Function.identity())));
    }

    private static Set<Long> editionIdsInOrder(List<Long> ids, Map<Long, MediaFile> byId) {
        Set<Long> editionIds = new LinkedHashSet<>();
        ids.forEach(id -> editionIds.add(byId.get(id).getEdition().getId()));
        return editionIds;
    }

    // ---- the film the files go to, and the other films with the same TMDB id --------------------------------------

    /**
     * The film the paired editions end up in, chosen the same way for the preflight and the write: the first film with
     * the TMDB id that the guard admits (the film they already sit in always counts), else their own film when it has
     * no id; null when a new film is needed.
     */
    private static Film destination(Long tmdbId, Film anchor, List<Film> sameId, List<Long> ids,
                                    Map<Long, MediaFile> byId) {
        if (tmdbId == null) {
            return anchor;
        }
        for (Film candidate : sameId) {
            if (candidate.getId().equals(anchor.getId())
                || ids.stream().allMatch(id -> GroupingGuard.accepts(candidate, byId.get(id).getPath()))) {
                return candidate;
            }
        }
        return anchor.getTmdbId() == null ? anchor : null;
    }

    /**
     * Untagged editions in the film that differ much in runtime from the incoming ones are flagged, never a reason to
     * refuse: the titles agree, so the runtime is only a warning sign.
     */
    private void flagRuntimeConflict(Film destination, List<Long> ids, Map<Long, MediaFile> byId, Instant at) {
        Map<Long, Edition> incoming = new LinkedHashMap<>();
        ids.forEach(id -> incoming.putIfAbsent(byId.get(id).getEdition().getId(), byId.get(id).getEdition()));
        String conflict = RuntimeSignal.conflict(incoming.values(), destination.getEditions());
        if (conflict != null) {
            flag(conflict, at);
        }
    }

    /**
     * Merges the other films with the TMDB id into the target, each only when the guard accepts the merge. Untagged
     * editions on either side that differ by more than 15% in runtime are flagged, and the films are merged anyway.
     * Films the guard refuses stay as they are.
     */
    private int mergeSameTmdb(List<Film> sameId, Long targetId, Instant at) {
        Film target = sameId.stream().filter(f -> f.getId().equals(targetId)).findFirst().orElse(null);
        if (target == null) {
            return 0;
        }
        List<Long> merging = new ArrayList<>();
        for (Film other : sameId) {
            if (other == target || !GroupingGuard.accepts(target, other)) {
                continue;
            }
            String conflict = RuntimeSignal.conflict(target.getEditions(), other.getEditions());
            if (conflict != null) {
                flag(conflict, at);
            }
            merging.add(other.getId());
        }
        merging.forEach(id -> ingest.merge(id, targetId));
        return merging.size();
    }

    /**
     * Versions that are untagged and whose runtimes differ from the first untagged version's by more than 15% are
     * flagged for the owner ("Runtimes differ by N%"). Every version stays its own paired edition: untagged theatrical
     * and extended cuts routinely differ that much, and the titles already agree. Versions are never part-joined.
     */
    private void flagRuntimeDifferences(FilmWrite write, Map<Long, MediaFile> byId) {
        MediaWrite reference = null;
        for (MediaWrite version : write.media()) {
            List<MediaFile> parts = version.fileIds().stream().map(byId::get).toList();
            boolean comparable = version.complete() && RuntimeSignal.durationsKnown(parts)
                && write.plex().editionTitle() == null
                && RuntimeSignal.untagged(parts.stream().map(MediaFile::getPath).toList());
            if (!comparable) {
                continue;
            }
            if (reference == null) {
                reference = version;
            } else if (RuntimeSignal.differs(runtime(byId, reference), runtime(byId, version))) {
                flag(RuntimeSignal.describe(runtime(byId, reference), runtime(byId, version),
                    byId.get(reference.fileIds().get(0)).getPath(), byId.get(version.fileIds().get(0)).getPath()),
                    write.importTime());
            }
        }
    }

    private static long runtime(Map<Long, MediaFile> byId, MediaWrite version) {
        return version.fileIds().stream().map(id -> byId.get(id).getDurationMs()).filter(Objects::nonNull)
            .mapToLong(Long::longValue).sum();
    }

    // ---- grouping in Plex's order ------------------------------------------------------------------------------

    /**
     * An edition that holds files Plex does not put in one version with them (or the same files in another order) is
     * unfolded: each of this film's files on its own, the files that are not part of this film together. Editions the
     * owner settled and editions with an unreadable duration are left alone. True when something was unfolded.
     */
    private boolean unfoldDisagreements(List<MediaWrite> media, List<Long> ids, Map<Long, MediaFile> byId,
                                        String title, Instant at) {
        Set<Long> kept = new HashSet<>(ids);
        boolean unfolded = false;
        for (Long editionId : editionIdsInOrder(ids, byId)) {
            Edition edition = editions.findById(editionId).orElseThrow();
            List<MediaFile> parts = edition.originalParts();
            List<Long> originals = parts.stream().map(MediaFile::getId).toList();
            boolean agrees = originals.size() < 2 || media.stream().anyMatch(m -> m.fileIds().equals(originals));
            if (agrees || edition.getGroupingSource() == GroupingSource.OWNER || !hasDurations(parts)) {
                continue;
            }
            List<List<Long>> groups = new ArrayList<>();
            List<Long> rest = originals.stream().filter(id -> !kept.contains(id)).toList();
            if (!rest.isEmpty()) {
                groups.add(rest);
            }
            originals.stream().filter(kept::contains).forEach(id -> groups.add(List.of(id)));
            join.unfold(editionId, groups);
            unfolded = true;
        }
        if (unfolded) {
            log(GroupingChange.SPLIT, "Separated files of \"" + title + "\" into editions of their own to match Plex",
                at);
        }
        return unfolded;
    }

    /**
     * Joins each version whose every file is paired into one edition, in Plex's order. A version that is only partly
     * paired is flagged and left as it is; so is one that cannot be joined safely (an edition that also holds other
     * files, one the owner settled, a file with no duration). A version already joined that way is left alone.
     */
    private void joinVersions(List<MediaWrite> media, List<Long> ids, String title, Instant at) {
        for (MediaWrite version : media) {
            if (version.complete() && version.fileIds().size() < 2) {
                continue;
            }
            if (!version.complete()) {
                flag("Only some of the files Plex lists for one version of \"" + title
                    + "\" are in the library, so they were not joined", at);
                continue;
            }
            Map<Long, MediaFile> byId = load(ids);
            List<MediaFile> parts = version.fileIds().stream().map(byId::get).toList();
            Set<Long> editionIds = editionIdsInOrder(version.fileIds(), byId);
            if (editionIds.size() == 1 && editions.findById(editionIds.iterator().next()).orElseThrow().originalParts()
                .stream().map(MediaFile::getId).toList().equals(version.fileIds())) {
                continue;
            }
            if (!joinable(parts, editionIds, version.fileIds())) {
                flag("The files Plex lists for one version of \"" + title + "\" could not be joined into one edition",
                    at);
                continue;
            }
            join.join(version.fileIds());
            log(GroupingChange.JOIN, "Joined " + version.fileIds().size() + " files of \"" + title
                + "\" into one edition to match Plex", at);
        }
    }

    /**
     * True when {@code EditionJoin} would accept the files and nothing would be lost: every part with a duration,
     * no edition of the owner's, and every edition involved holding only these files (a rendition counts).
     */
    private boolean joinable(List<MediaFile> parts, Set<Long> editionIds, List<Long> fileIds) {
        if (!hasDurations(parts)) {
            return false;
        }
        for (Long editionId : editionIds) {
            Edition edition = editions.findById(editionId).orElseThrow();
            boolean owned = edition.getGroupingSource() == GroupingSource.OWNER;
            boolean holdsOthers = edition.getMediaFiles().stream().anyMatch(f -> !fileIds.contains(f.getId()));
            if (owned || holdsOthers) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDurations(List<MediaFile> parts) {
        return parts.stream().allMatch(p -> p.getDurationMs() != null && p.getDurationMs() > 0);
    }

    /** Moves each paired edition into the target film, gathering the film it came from when that is left empty. */
    private int moveEditions(Set<Long> editionIds, Long targetId) {
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
        return moved;
    }

    /**
     * Each version is an edition named by Plex's edition title, else by the name it has; names are unique within the
     * film (case-insensitive), so a name already taken by another edition gets " 2", " 3"... (Original, Original 2).
     */
    private static void nameVersions(Film film, PlexFilm plex, List<Long> versionEditions) {
        Set<String> taken = new HashSet<>();
        film.getEditions().stream().filter(e -> !versionEditions.contains(e.getId()))
            .forEach(e -> taken.add(e.getName().toLowerCase(Locale.ROOT)));
        for (Long editionId : versionEditions) {
            Edition edition = film.getEditions().stream().filter(e -> e.getId().equals(editionId)).findFirst()
                .orElse(null);
            if (edition == null) {
                continue;
            }
            if (edition.getGroupingSource() != GroupingSource.OWNER) {
                String wanted = plex.editionTitle() == null ? edition.getName()
                    : cut(plex.editionTitle(), EDITION_NAME_MAX);
                edition.setName(unique(wanted, taken));
            }
            taken.add(edition.getName().toLowerCase(Locale.ROOT));
        }
    }

    static String unique(String wanted, Set<String> taken) {
        if (!taken.contains(wanted.toLowerCase(Locale.ROOT))) {
            return wanted;
        }
        for (int n = 2; ; n++) {
            String suffix = " " + n;
            String base = cut(wanted, EDITION_NAME_MAX - suffix.length());
            if (!taken.contains((base + suffix).toLowerCase(Locale.ROOT))) {
                return base + suffix;
            }
        }
    }

    /**
     * The film opens on the first version Plex lists, unless the owner chose one or an earlier Plex film of the same
     * film already did (its default is an edition the import settled). It is recorded even while the film has one
     * edition, so a later Plex film that adds another does not take it. Plex's choice is not the owner's: it does not
     * set {@code defaultEditionChosen}.
     */
    private void chooseDefault(Film film, Long firstEditionId) {
        FilmPreference preference = preferences.findById(film.getId()).orElse(null);
        if (preference != null) {
            if (preference.isDefaultEditionChosen()) {
                return;
            }
            boolean settledByPlex = film.getEditions().stream().anyMatch(e -> e.getId().equals(
                preference.getDefaultEditionId()) && e.getGroupingSource() == GroupingSource.PLEX);
            if (settledByPlex) {
                return;
            }
        }
        FilmPreference target = preference == null ? new FilmPreference(film.getId()) : preference;
        target.setDefaultEditionId(firstEditionId);
        preferences.save(target);
    }

    private void flag(String summary, Instant at) {
        String text = cut(summary, SUMMARY_MAX);
        if (!changes.existsByKindAndSummary(GroupingChange.FLAG, text)) {
            changes.save(new GroupingChange(at, GroupingChange.FLAG, text));
        }
    }

    private void log(String kind, String summary, Instant at) {
        changes.save(new GroupingChange(at, kind, cut(summary, SUMMARY_MAX)));
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
