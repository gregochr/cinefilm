package gregochr.cinefilm.library;

import gregochr.cinefilm.collection.CollectionPart;
import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.collection.FilmCollectionRepository;
import gregochr.cinefilm.film.AudioStream;
import gregochr.cinefilm.film.Credit;
import gregochr.cinefilm.film.CreditRole;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.person.Person;
import gregochr.cinefilm.person.PersonRepository;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionRef;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The database side of a scan, one short transaction per operation so a slow probe or TMDB call never holds one
 * open. A file is identified by its path relative to the library root; nothing here ever deletes a film, an edition
 * or its watch history.
 */
@Service
public class LibraryIngest {

    private static final Logger LOG = LoggerFactory.getLogger(LibraryIngest.class);
    private static final int NAME_MAX = 255;
    private static final int OVERVIEW_MAX = 4000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FilmRepository films;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final PersonRepository people;
    private final FilmCollectionRepository collections;
    private final ScanRunRepository runs;
    private final TmdbApi tmdb;

    /** Creates the ingest over the repositories and TMDB (for collection parts). */
    public LibraryIngest(FilmRepository films, EditionRepository editions, MediaFileRepository files,
                         PersonRepository people, FilmCollectionRepository collections, ScanRunRepository runs,
                         TmdbApi tmdb) {
        this.films = films;
        this.editions = editions;
        this.files = files;
        this.people = people;
        this.collections = collections;
        this.runs = runs;
        this.tmdb = tmdb;
    }

    /**
     * A film waiting for a TMDB lookup.
     *
     * @param filmId the film
     * @param title its current (guessed) title
     * @param year its current year, null when unknown
     * @param runtimeMs the runtime of its first playable edition
     */
    public record Pending(Long filmId, String title, Integer year, long runtimeMs) {
    }

    /** Every stored original file keyed by relative path. */
    @Transactional(readOnly = true)
    public Map<String, Known> known() {
        Map<String, Known> known = new HashMap<>();
        files.findKnownOriginals().forEach(k -> known.put(k.path(), k));
        return known;
    }

    /** Starts the record of a scan. */
    @Transactional
    public Long startRun(String trigger, Instant now) {
        ScanRun run = new ScanRun();
        run.setStartedAt(now);
        run.setTrigger(trigger);
        return runs.save(run).getId();
    }

    /** Completes the record of a scan. */
    @Transactional
    public void finishRun(Long id, Instant now, int seen, int added, int missing, String error) {
        ScanRun run = runs.findById(id).orElseThrow();
        run.setFinishedAt(now);
        run.setFilesSeen(seen);
        run.setNewFiles(added);
        run.setMissingFiles(missing);
        run.setError(cut(error, 500));
        runs.save(run);
    }

    /**
     * Records a file seen for the first time. Two files matching one TMDB id become two editions of one film.
     *
     * @param found the file on disk
     * @param playability whether it can be played
     * @param probe what ffprobe found, or null when it could not be read
     * @param guess the title and year guessed from the file
     * @param outcome the TMDB lookup, or null when none was attempted
     */
    @Transactional
    public void addNew(FoundFile found, Playability playability, ProbeResult probe, TitleGuess guess,
                       MatchOutcome outcome) {
        Film film = null;
        if (outcome != null && outcome.status() == Status.MATCHED) {
            film = films.findFirstByTmdbIdOrderByIdAsc(outcome.movie().id()).orElse(null);
        }
        if (film == null) {
            film = newFilm(found, probe, guess);
            if (outcome != null && outcome.status() == Status.MATCHED) {
                applyMovie(film, outcome.movie());
            } else if (outcome != null && outcome.status() == Status.NEEDS_MATCH) {
                markNeedsMatch(film, outcome);
            }
        }
        Edition edition = new Edition();
        edition.setFilm(film);
        edition.setName("Original");
        edition.setYear(film.getYear() == 0 ? null : film.getYear());
        edition.setRuntimeMs(probe == null ? 0 : probe.durationMs());
        MediaFile file = new MediaFile();
        file.setEdition(edition);
        file.setAddedAt(found.createdAt());
        fill(file, found, playability, probe);
        edition.getMediaFiles().add(file);
        film.getEditions().add(edition);
        films.save(film);
    }

    /** Updates a known file whose size or modified time changed, or that could not be read last time. */
    @Transactional
    public void refresh(Long mediaFileId, FoundFile found, Playability playability, ProbeResult probe) {
        MediaFile file = files.findById(mediaFileId).orElseThrow();
        fill(file, found, playability, probe);
        if (probe != null) {
            file.getEdition().setRuntimeMs(probe.durationMs());
        }
    }

    /** A file that had gone missing is back. */
    @Transactional
    public void markPresent(Long mediaFileId) {
        files.findById(mediaFileId).orElseThrow().setPresence(Presence.PRESENT);
    }

    /** A renamed or moved file: the same bytes under a new path. The edition and its history stay. */
    @Transactional
    public void repoint(Long mediaFileId, FoundFile found) {
        MediaFile file = files.findById(mediaFileId).orElseThrow();
        file.setPath(found.path());
        file.setPresence(Presence.PRESENT);
    }

    /** Marks files that were not found as missing. Nothing is deleted. */
    @Transactional
    public void markMissing(Collection<Long> mediaFileIds) {
        files.findAllById(mediaFileIds).forEach(f -> f.setPresence(Presence.MISSING));
    }

    /** Films that are waiting for a TMDB lookup and have a playable file. */
    @Transactional(readOnly = true)
    public List<Pending> pending() {
        return films.findByMatchStatusOrderBySortTitle(MatchStatus.PENDING).stream()
            .filter(f -> !f.getEditions().isEmpty() && f.browsable())
            .map(f -> new Pending(f.getId(), f.getTitle(), f.getYear() == 0 ? null : f.getYear(),
                f.getEditions().get(0).getRuntimeMs()))
            .toList();
    }

    /** Applies the result of a retried lookup. A TMDB outage changes nothing. */
    @Transactional
    public void applyOutcome(Long filmId, MatchOutcome outcome) {
        if (outcome.status() == Status.UNAVAILABLE) {
            return;
        }
        Film film = films.findById(filmId).orElseThrow();
        if (outcome.status() == Status.NEEDS_MATCH) {
            markNeedsMatch(film, outcome);
            return;
        }
        Film existing = films.findFirstByTmdbIdOrderByIdAsc(outcome.movie().id()).orElse(null);
        if (existing == null || existing.getId().equals(filmId)) {
            applyMovie(film, outcome.movie());
            return;
        }
        editions.moveAll(film, existing);
        films.delete(films.findById(filmId).orElseThrow());
    }

    private Film newFilm(FoundFile found, ProbeResult probe, TitleGuess guess) {
        Film film = new Film();
        film.setTitleAndSort(cut(guess.title(), NAME_MAX));
        film.setYear(guess.year() == null ? 0 : guess.year());
        film.setAddedAt(found.createdAt());
        film.setMatchStatus(MatchStatus.PENDING);
        if (probe != null && probe.tags().description() != null) {
            film.setOverview(cut(probe.tags().description(), OVERVIEW_MAX));
        }
        return film;
    }

    private void markNeedsMatch(Film film, MatchOutcome outcome) {
        film.setMatchStatus(MatchStatus.NEEDS_MATCH);
        List<Candidate> candidates = outcome.candidates().stream()
            .map(c -> new Candidate(c.tmdbId(), cut(c.title(), 100), c.year(), c.runtimeMinutes(), c.posterPath(),
                c.score()))
            .toList();
        film.setMatchCandidates(JSON.writeValueAsString(candidates));
    }

    private void applyMovie(Film film, MovieDetails movie) {
        film.setTmdbId(movie.id());
        if (movie.title() != null) {
            film.setTitleAndSort(cut(movie.title(), NAME_MAX));
        }
        if (movie.year() != null) {
            film.setYear(movie.year());
        }
        film.setOverview(movie.overview() == null ? film.getOverview() : cut(movie.overview(), OVERVIEW_MAX));
        film.setPosterPath(movie.posterPath());
        film.setBackdropPath(movie.backdropPath());
        film.getGenres().clear();
        movie.genres().forEach(g -> film.getGenres().add(cut(g, 100)));
        film.setMatchStatus(MatchStatus.MATCHED);
        film.setMatchCandidates(null);
        film.setCollection(movie.collection() == null ? null : collection(movie.collection()));
        film.getCredits().clear();
        int order = 0;
        for (var director : movie.directors()) {
            film.getCredits().add(credit(film, director, CreditRole.DIRECTOR, null, order++));
        }
        order = 0;
        for (var actor : movie.cast()) {
            film.getCredits().add(credit(film, actor, CreditRole.CAST, actor.character(), order++));
        }
    }

    private Credit credit(Film film, gregochr.cinefilm.tmdb.TmdbModels.Credit source, CreditRole role,
                          String character, int order) {
        Person person = people.findFirstByTmdbIdOrderByIdAsc(source.tmdbId()).orElseGet(() -> {
            Person created = new Person(cut(source.name() == null ? "Unknown" : source.name(), NAME_MAX));
            created.setTmdbId(source.tmdbId());
            created.setProfilePath(source.profilePath());
            return people.save(created);
        });
        Credit credit = new Credit();
        credit.setFilm(film);
        credit.setPerson(person);
        credit.setRole(role);
        credit.setCharacter(cut(character, NAME_MAX));
        credit.setSequence(order);
        return credit;
    }

    private FilmCollection collection(CollectionRef ref) {
        return collections.findFirstByTmdbIdOrderByIdAsc(ref.id()).orElseGet(() -> {
            FilmCollection created = new FilmCollection();
            created.setTmdbId(ref.id());
            created.setName(cut(ref.name() == null ? "Collection" : ref.name(), NAME_MAX));
            created.setPosterPath(ref.posterPath());
            try {
                tmdb.collection(ref.id()).ifPresent(details -> addParts(created, details));
            } catch (TmdbUnavailableException e) {
                LOG.warn("TMDB collection {} unavailable; stored without its parts", ref.id());
            }
            return collections.save(created);
        });
    }

    private static void addParts(FilmCollection collection, CollectionDetails details) {
        int order = 0;
        for (var p : details.parts()) {
            CollectionPart part = new CollectionPart();
            part.setCollection(collection);
            part.setTmdbId(p.id());
            part.setTitle(cut(p.title() == null ? "Untitled" : p.title(), NAME_MAX));
            part.setYear(p.year());
            part.setReleaseOrder(order++);
            part.setPosterPath(p.posterPath());
            collection.getParts().add(part);
        }
    }

    private static void fill(MediaFile file, FoundFile found, Playability playability, ProbeResult probe) {
        file.setPath(found.path());
        file.setSizeBytes(found.sizeBytes());
        file.setModifiedAt(found.modifiedAt());
        file.setPresence(Presence.PRESENT);
        file.setPlayability(playability);
        file.getAudioStreams().clear();
        file.getSubtitleStreams().clear();
        if (probe == null) {
            return;
        }
        file.setContainer(cut(probe.container(), 20));
        file.setVideoCodec(cut(probe.videoCodec(), 40));
        file.setWidth(probe.width());
        file.setHeight(probe.height());
        file.setBitrate(probe.bitrate());
        file.setDurationMs(probe.durationMs());
        for (ProbeResult.Audio a : probe.audio()) {
            AudioStream stream = new AudioStream();
            stream.setMediaFile(file);
            stream.setIndex(a.index());
            stream.setLanguage(cut(a.language(), 40));
            stream.setChannels(a.channels());
            stream.setCodec(cut(a.codec(), 40));
            stream.setTitle(cut(a.title(), NAME_MAX));
            stream.setDefault(a.isDefault());
            stream.setSafariPlayable(a.safariPlayable());
            file.getAudioStreams().add(stream);
        }
        for (ProbeResult.Subtitle s : probe.subtitles()) {
            SubtitleStream stream = new SubtitleStream();
            stream.setMediaFile(file);
            stream.setIndex(s.index());
            stream.setLanguage(cut(s.language(), 40));
            stream.setForced(s.forced());
            stream.setTitle(cut(s.title(), NAME_MAX));
            stream.setFormat(s.format());
            file.getSubtitleStreams().add(stream);
        }
    }

    private static String cut(String text, int max) {
        return text == null ? null : text.substring(0, Math.min(text.length(), max));
    }
}
