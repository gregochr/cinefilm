package gregochr.cinefilm.library;

import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmPreference;
import gregochr.cinefilm.film.FilmPreferenceRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaKind;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.library.StateFolding.Source;
import gregochr.cinefilm.library.StateFolding.State;
import gregochr.cinefilm.playback.PlaybackState;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Joins files into one multi-part edition, and unfolds one back into several: the single operation behind the scan's
 * auto-resolve (and, in later phases, the Plex import and Edit editions).
 *
 * <p><strong>The order of the steps matters</strong>, because every bulk statement flushes and then clears the
 * persistence context (entities loaded before it are detached): films merge first, files move by one bulk update,
 * then the survivor is reloaded before anything is written to it, the playback state is folded onto it, the absorbed
 * editions' state and then the editions themselves are deleted by id (state first: the foreign key), and last the
 * remembered choices are repointed. File ids, stream ids and keyframe rows never change: only the file's edition does.
 */
@Service
public class EditionJoin {

    private final FilmRepository films;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final PlaybackStateRepository states;
    private final FilmPreferenceRepository preferences;
    private final LibraryIngest ingest;

    /** Creates the operation over the repositories and the ingest (whose film merge it reuses). */
    public EditionJoin(FilmRepository films, EditionRepository editions, MediaFileRepository files,
                       PlaybackStateRepository states, FilmPreferenceRepository preferences, LibraryIngest ingest) {
        this.films = films;
        this.editions = editions;
        this.files = files;
        this.states = states;
        this.preferences = preferences;
        this.ingest = ingest;
    }

    /** One edition going into a join, as it was before anything moved. */
    private record Piece(Long editionId, int first, int last, Long marker, State state) {
    }

    /**
     * Joins files into the edition of the first one: they become its parts 1..n in the order given. Editions that end
     * up empty are deleted after their playback state is folded onto the survivor, and films that held them are merged
     * (a matched film survives, else the oldest).
     *
     * @param orderedFileIds the original files in play order; at least two, each with a duration above 0
     * @return the id of the surviving edition
     * @throws IllegalArgumentException when a file is unknown, is not an original, repeats, or has no duration
     */
    @Transactional
    public Long join(List<Long> orderedFileIds) {
        List<MediaFile> parts = load(orderedFileIds);
        refuseIfAnAbsorbedEditionKeepsFiles(parts, orderedFileIds);
        List<Long> durations = parts.stream().map(MediaFile::timelineDurationMs).toList();
        EditionTimeline timeline = EditionTimeline.of(durations);
        Long survivorId = parts.get(0).getEdition().getId();
        List<Piece> pieces = pieces(parts);
        Set<Long> filmIds = new LinkedHashSet<>();
        Set<Long> absorbed = new LinkedHashSet<>();
        List<Long> moving = new ArrayList<>();
        for (MediaFile part : parts) {
            filmIds.add(part.getEdition().getFilm().getId());
            if (!part.getEdition().getId().equals(survivorId)) {
                absorbed.add(part.getEdition().getId());
                moving.add(part.getId());
            }
        }
        List<Source> sources = new ArrayList<>();
        Piece lastHolder = null;
        for (Piece piece : pieces) {
            boolean holdsLast = piece.last() == parts.size() - 1;
            if (holdsLast) {
                lastHolder = piece;
            }
            sources.add(new Source(timeline.partStartMs(piece.first()),
                timeline.partStartMs(piece.last()) + durations.get(piece.last()), holdsLast, piece.state()));
        }
        Long marker = lastHolder.marker() == null ? null
            : lastHolder.marker() + timeline.partStartMs(lastHolder.first());

        // 1. Films first (this clears the persistence context).
        Long filmId = mergeFilms(filmIds);
        // 2. Move the files with one bulk update (flushes, then clears).
        if (!moving.isEmpty()) {
            files.moveToEdition(moving, editions.getReferenceById(survivorId));
        }
        // 3. Reload, number the parts, recompute the runtime and the credits marker.
        Edition survivor = editions.findById(survivorId).orElseThrow();
        Map<Long, MediaFile> byId = new HashMap<>();
        survivor.getMediaFiles().forEach(f -> byId.put(f.getId(), f));
        for (int i = 0; i < orderedFileIds.size(); i++) {
            byId.get(orderedFileIds.get(i)).setPartNumber(i + 1);
        }
        survivor.recomputeRuntime();
        survivor.setCreditsMarkerMs(marker);
        // 4. Fold the playback state onto the survivor and save it.
        State folded = StateFolding.fold(sources, timeline.totalMs());
        if (folded != null) {
            states.save(apply(states.findById(survivorId).orElseGet(() -> new PlaybackState(survivorId)), folded));
        }
        // 5. The absorbed editions' state first (the foreign key), then the editions by id.
        if (!absorbed.isEmpty()) {
            states.deleteByEditionIds(absorbed);
            editions.deleteByIds(absorbed);
        }
        // 6. Remembered choices.
        repoint(filmId, survivorId, absorbed);
        return survivorId;
    }

    /**
     * Splits one multi-part edition into several: the first group keeps the edition (and its id and name), every other
     * group becomes a new edition of the same film with the same name. State, runtimes, part numbers and the credits
     * marker follow the {@link StateFolding} unfolding rules.
     *
     * @param editionId the edition to split
     * @param fileGroups the files of each new edition, in play order; together exactly the edition's original files
     * @return the ids of the resulting editions, the original's first
     * @throws IllegalArgumentException when the groups are not a partition of the edition's files
     */
    @Transactional
    public List<Long> unfold(Long editionId, List<List<Long>> fileGroups) {
        Edition edition = editions.findById(editionId).orElseThrow();
        List<MediaFile> parts = edition.originalParts();
        Map<Long, Integer> indexOf = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            indexOf.put(parts.get(i).getId(), i);
        }
        List<List<Integer>> groups = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (List<Long> group : fileGroups) {
            List<Integer> indexes = new ArrayList<>();
            for (Long id : group) {
                if (!indexOf.containsKey(id) || !seen.add(id)) {
                    throw new IllegalArgumentException("File " + id + " is not one of the edition's parts, or repeats");
                }
                indexes.add(indexOf.get(id));
            }
            indexes.sort(Comparator.naturalOrder());
            groups.add(indexes);
        }
        if (fileGroups.size() < 2 || seen.size() != parts.size() || groups.stream().anyMatch(List::isEmpty)) {
            throw new IllegalArgumentException("The groups must split all of the edition's files, at least two ways");
        }
        List<Long> durations = parts.stream().map(MediaFile::timelineDurationMs).toList();
        EditionTimeline.of(durations); // every part needs a duration
        List<List<Long>> orderedIds = new ArrayList<>();
        for (List<Integer> group : groups) {
            orderedIds.add(group.stream().map(i -> parts.get(i).getId()).toList());
        }
        Long markerMs = edition.getCreditsMarkerMs();
        State prior = states.findById(editionId).map(EditionJoin::stateOf).orElse(null);
        Film film = edition.getFilm();
        List<Long> ids = new ArrayList<>();
        ids.add(editionId);
        for (int g = 1; g < groups.size(); g++) {
            Edition created = new Edition();
            created.setFilm(film);
            created.setName(edition.getName());
            created.setYear(edition.getYear());
            created.setGroupingSource(edition.getGroupingSource());
            ids.add(editions.saveAndFlush(created).getId());
        }
        for (int g = 1; g < groups.size(); g++) {
            files.moveToEdition(orderedIds.get(g), editions.getReferenceById(ids.get(g)));
        }
        StateFolding.Located marker = markerMs == null ? null : StateFolding.relocate(markerMs, durations, groups);
        for (int g = 0; g < groups.size(); g++) {
            Edition target = editions.findById(ids.get(g)).orElseThrow();
            Map<Long, MediaFile> byId = new HashMap<>();
            target.getMediaFiles().forEach(f -> byId.put(f.getId(), f));
            for (int i = 0; i < orderedIds.get(g).size(); i++) {
                byId.get(orderedIds.get(g).get(i)).setPartNumber(i + 1);
            }
            target.recomputeRuntime();
            target.setCreditsMarkerMs(marker != null && marker.group() == g ? marker.positionMs() : null);
        }
        List<State> split = StateFolding.unfold(prior, durations, groups);
        for (int g = 0; g < groups.size(); g++) {
            Long id = ids.get(g);
            if (split.get(g) != null) {
                states.save(apply(states.findById(id).orElseGet(() -> new PlaybackState(id)), split.get(g)));
            } else if (g == 0) {
                states.deleteById(editionId);
            }
        }
        return ids;
    }

    /** An absorbed edition is deleted, so it must hold no file that is not moving into the survivor. */
    private void refuseIfAnAbsorbedEditionKeepsFiles(List<MediaFile> parts, List<Long> orderedFileIds) {
        Long survivor = parts.get(0).getEdition().getId();
        Set<Long> absorbed = new LinkedHashSet<>();
        Set<Long> filmIds = new LinkedHashSet<>();
        parts.forEach(p -> {
            absorbed.add(p.getEdition().getId());
            filmIds.add(p.getEdition().getFilm().getId());
        });
        absorbed.remove(survivor);
        refuseIfAFilmHoldsAnotherTitle(parts.get(0), filmIds);
        for (Long editionId : absorbed) {
            for (MediaFile file : editions.findById(editionId).orElseThrow().getMediaFiles()) {
                if (file.getKind() != MediaKind.ORIGINAL || !orderedFileIds.contains(file.getId())) {
                    throw new IllegalArgumentException("Edition " + editionId + " still holds file " + file.getId()
                        + " that is not part of the join");
                }
            }
        }
    }

    /** Joining across films merges them whole, so no film involved may hold a file with another title than part 1. */
    private void refuseIfAFilmHoldsAnotherTitle(MediaFile first, Set<Long> filmIds) {
        if (filmIds.size() < 2) {
            return;
        }
        String title = FileNameParser.parse(first.getPath()).comparisonTitle();
        for (Film film : films.findAllById(filmIds)) {
            for (Edition edition : film.getEditions()) {
                for (MediaFile file : edition.originalParts()) {
                    if (!FileNameParser.parse(file.getPath()).comparisonTitle().equals(title)) {
                        throw new IllegalArgumentException("Film " + film.getId() + " also holds file " + file.getId()
                            + " with another title, so its films cannot be merged");
                    }
                }
            }
        }
    }

    private List<MediaFile> load(List<Long> orderedFileIds) {
        if (orderedFileIds.size() < 2 || new LinkedHashSet<>(orderedFileIds).size() != orderedFileIds.size()) {
            throw new IllegalArgumentException("A join needs at least two different files");
        }
        Map<Long, MediaFile> found = new HashMap<>();
        files.findAllById(orderedFileIds).forEach(f -> found.put(f.getId(), f));
        List<MediaFile> parts = new ArrayList<>();
        for (Long id : orderedFileIds) {
            MediaFile file = found.get(id);
            if (file == null || file.getKind() != MediaKind.ORIGINAL) {
                throw new IllegalArgumentException("File " + id + " is not an original file");
            }
            parts.add(file);
        }
        return parts;
    }

    /** Each edition involved with the first and last position its files take, and its state and marker. */
    private List<Piece> pieces(List<MediaFile> parts) {
        Map<Long, int[]> span = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            int index = i;
            int[] range = span.computeIfAbsent(parts.get(i).getEdition().getId(), k -> new int[] {index, index});
            range[1] = i;
        }
        List<Piece> pieces = new ArrayList<>();
        for (Map.Entry<Long, int[]> entry : span.entrySet()) {
            Edition edition = editions.findById(entry.getKey()).orElseThrow();
            pieces.add(new Piece(entry.getKey(), entry.getValue()[0], entry.getValue()[1],
                edition.getCreditsMarkerMs(), states.findById(entry.getKey()).map(EditionJoin::stateOf).orElse(null)));
        }
        return pieces;
    }

    /** Merges the films into one (a matched film survives, else the oldest) and returns the survivor's id. */
    private Long mergeFilms(Set<Long> filmIds) {
        if (filmIds.size() == 1) {
            return filmIds.iterator().next();
        }
        List<Film> all = films.findAllById(filmIds);
        Long survivor = all.stream().filter(f -> f.getMatchStatus() == MatchStatus.MATCHED).map(Film::getId)
            .min(Comparator.naturalOrder())
            .orElseGet(() -> all.stream().map(Film::getId).min(Comparator.naturalOrder()).orElseThrow());
        for (Long id : filmIds) {
            if (!id.equals(survivor)) {
                ingest.merge(id, survivor);
            }
        }
        return survivor;
    }

    /** Points the remembered edition choices at the survivor and the stream choices at part 1's streams. */
    private void repoint(Long filmId, Long survivorId, Set<Long> absorbed) {
        FilmPreference preference = preferences.findById(filmId).orElse(null);
        if (preference == null) {
            return;
        }
        if (absorbed.contains(preference.getDefaultEditionId())) {
            preference.setDefaultEditionId(survivorId);
        }
        if (absorbed.contains(preference.getLastEditionId())) {
            preference.setLastEditionId(survivorId);
        }
        List<MediaFile> parts = editions.findById(survivorId).orElseThrow().originalParts();
        remapAudio(preference, parts);
        remapSubtitle(preference, parts);
        preferences.save(preference);
    }

    /**
     * An audio choice that points into a later part moves to part 1's stream with the same language (the one of the
     * same rank when several share it), else the same rank, else it is cleared.
     */
    private static void remapAudio(FilmPreference preference, List<MediaFile> parts) {
        Long chosen = preference.getAudioStreamId();
        if (chosen == null) {
            return;
        }
        List<AudioStream> first = sortedAudio(parts.get(0));
        for (MediaFile part : parts.subList(1, parts.size())) {
            List<AudioStream> streams = sortedAudio(part);
            for (int rank = 0; rank < streams.size(); rank++) {
                if (chosen.equals(streams.get(rank).getId())) {
                    String language = streams.get(rank).getLanguage();
                    preference.setAudioStreamId(equivalent(first, rank,
                        s -> language != null && language.equalsIgnoreCase(s.getLanguage()))
                        .map(AudioStream::getId).orElse(null));
                    return;
                }
            }
        }
    }

    /**
     * A subtitle choice (the stream's id as text, or "off") that points into a later part moves to part 1's stream with
     * the same language and forced flag, else the same rank, else it is turned off.
     */
    private static void remapSubtitle(FilmPreference preference, List<MediaFile> parts) {
        String chosen = preference.getSubtitle();
        if (chosen == null) {
            return;
        }
        List<SubtitleStream> first = sortedSubtitles(parts.get(0));
        for (MediaFile part : parts.subList(1, parts.size())) {
            List<SubtitleStream> streams = sortedSubtitles(part);
            for (int rank = 0; rank < streams.size(); rank++) {
                SubtitleStream stream = streams.get(rank);
                if (chosen.equals(String.valueOf(stream.getId()))) {
                    preference.setSubtitle(equivalent(first, rank, s -> Objects.equals(s.getLanguage(),
                        stream.getLanguage()) && s.isForced() == stream.isForced())
                        .map(s -> String.valueOf(s.getId())).orElse("off"));
                    return;
                }
            }
        }
    }

    /** The part 1 stream standing in for the one of a rank: a like one (that rank's if it is), else that rank. */
    private static <T> Optional<T> equivalent(List<T> first, int rank, Predicate<T> alike) {
        if (rank < first.size() && alike.test(first.get(rank))) {
            return Optional.of(first.get(rank));
        }
        Optional<T> like = first.stream().filter(alike).findFirst();
        if (like.isPresent()) {
            return like;
        }
        return rank < first.size() ? Optional.of(first.get(rank)) : Optional.empty();
    }

    private static List<AudioStream> sortedAudio(MediaFile file) {
        return file.getAudioStreams().stream().sorted(Comparator.comparingInt(AudioStream::getIndex)).toList();
    }

    private static List<SubtitleStream> sortedSubtitles(MediaFile file) {
        return file.getSubtitleStreams().stream().sorted(Comparator.comparingInt(SubtitleStream::getIndex)).toList();
    }

    private static State stateOf(PlaybackState state) {
        return new State(state.getPositionMs(), state.isWatched(), state.getWatchedAt(), state.getLastPlayedAt(),
            state.isHiddenFromContinueWatching(), state.getHiddenAt());
    }

    private static PlaybackState apply(PlaybackState target, State state) {
        target.setPositionMs(state.positionMs());
        target.setWatched(state.watched());
        target.setWatchedAt(state.watchedAt());
        target.setLastPlayedAt(state.lastPlayedAt());
        target.setHiddenFromContinueWatching(state.hidden());
        target.setHiddenAt(state.hiddenAt());
        return target;
    }
}
