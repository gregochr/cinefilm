package gregochr.cinefilm.plex;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.library.ScanRunRepository;
import gregochr.cinefilm.plex.PlexDtos.LastImport;
import gregochr.cinefilm.plex.PlexDtos.PlexImportReport;
import gregochr.cinefilm.plex.PlexDtos.PlexImportStatus;
import gregochr.cinefilm.plex.PlexDtos.PlexProgress;
import gregochr.cinefilm.plex.PlexFilmWriter.Applied;
import gregochr.cinefilm.plex.PlexFilmWriter.FilmWrite;
import gregochr.cinefilm.plex.PlexFilmWriter.Preflight;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexImage;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the library from Plex, once, on demand: pairs each Plex film's files with CineFilm's, then per film fetches
 * TMDB by id and the artwork (outside any transaction) and writes the result in one short transaction. It runs after a
 * completed scan, under the same lock as a scan, and only ever reads from Plex. Nothing is written when no file pairs;
 * a failure on one film leaves the others committed; a second run changes nothing.
 */
@Service
public class PlexImportService {

    private static final Logger LOG = LoggerFactory.getLogger(PlexImportService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int POSTER_WIDTH = 600;
    static final int POSTER_HEIGHT = 900;
    static final int BACKDROP_WIDTH = 1920;
    static final int BACKDROP_HEIGHT = 1080;

    private final PlexApi plex;
    private final TmdbApi tmdb;
    private final LibraryIngest ingest;
    private final PlexFilmWriter writer;
    private final ArtworkStore artwork;
    private final ScanRunRepository scans;
    private final PlexImportRunRepository runs;
    private final LibraryLock lock;
    private final Executor executor;
    private final Clock clock;
    private final String prefix;
    private final ApplicationEventPublisher events;
    private final AtomicReference<PlexProgress> progress = new AtomicReference<>();

    /** Creates the service; the executor runs imports in the background and the lock keeps them apart from scans. */
    public PlexImportService(PlexApi plex, TmdbApi tmdb, LibraryIngest ingest, PlexFilmWriter writer,
                             ArtworkStore artwork, ScanRunRepository scans, PlexImportRunRepository runs,
                             LibraryLock lock, @Qualifier("plexImportExecutor") Executor executor, Clock clock,
                             @Value("${cinefilm.plex.path-prefix}") String pathPrefix,
                             ApplicationEventPublisher events) {
        this.plex = plex;
        this.tmdb = tmdb;
        this.ingest = ingest;
        this.writer = writer;
        this.artwork = artwork;
        this.scans = scans;
        this.runs = runs;
        this.lock = lock;
        this.executor = executor;
        this.clock = clock;
        this.prefix = PlexRules.prefix(pathPrefix);
        this.events = events;
    }

    /** A Plex film and the CineFilm files it pairs with, in the order Plex lists them. */
    record Pairing(PlexFilm film, List<Long> mediaFileIds) {
    }

    /** True while an import is running. */
    public boolean isRunning() {
        return lock.heldBy(LibraryLock.IMPORT);
    }

    /**
     * The Plex import panel: whether it is set up, whether Plex answers, progress, and the last import's report.
     * Plex is asked only when no import is running (a running import obviously reaches it).
     */
    public PlexImportStatus status() {
        if (!plex.configured()) {
            return new PlexImportStatus(false, false, false, null, null);
        }
        boolean running = isRunning();
        LastImport last = runs.findFirstByFinishedAtNotNullOrderByIdDesc()
            .map(r -> new LastImport(r.getStartedAt(), r.getFinishedAt(), r.getError(), report(r.getReport())))
            .orElse(null);
        return new PlexImportStatus(true, running || plex.reachable(), running, running ? progress.get() : null,
            last);
    }

    private static PlexImportReport report(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, PlexImportReport.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * Starts an import in the background.
     *
     * @throws NotFoundException when Plex is not configured
     * @throws ConflictException when a scan or import is running, or no scan has completed yet
     */
    public void start() {
        if (!plex.configured()) {
            throw new NotFoundException("Plex is not configured");
        }
        if (!lock.tryAcquire(LibraryLock.IMPORT)) {
            throw new ConflictException("A scan or import is already running");
        }
        try {
            if (!scans.existsByFinishedAtNotNullAndErrorIsNull()) {
                throw new ConflictException("Run a library scan first");
            }
            progress.set(new PlexProgress(0, 0, null));
            executor.execute(() -> {
                try {
                    execute();
                } finally {
                    lock.release();
                }
            });
        } catch (RuntimeException e) {
            lock.release();
            throw e;
        }
    }

    private void execute() {
        PlexImportRun run = new PlexImportRun();
        run.setStartedAt(clock.instant());
        run = runs.save(run);
        try {
            run.setReport(JSON.writeValueAsString(importAll(run.getStartedAt())));
        } catch (PlexException e) {
            run.setError(e.getMessage());
            LOG.warn("Plex import stopped: {}", e.getMessage());
        } catch (RuntimeException e) {
            run.setError("Import failed: " + e.getClass().getSimpleName());
            LOG.error("Plex import failed", e);
        }
        run.setFinishedAt(clock.instant());
        runs.save(run);
        LOG.info("Plex import finished, note: {}", run.getError());
        events.publishEvent(new PlexImportFinished());
    }

    /** The counters and capped lists of one import. */
    private static final class Tally {
        private int plexFilms;
        private int paired;
        private int merged;
        private int editionsMoved;
        private int metadataApplied;
        private int postersStored;
        private int backdropsStored;
        private int artworkFailures;
        private int invalidArtPaths;
        private int tmdbUnavailable;
        private int watchedImported;
        private int resumePointsImported;
        private int keptNewer;
        private int failures;
        private final List<String> failedFilms = new ArrayList<>();
        private final List<String> notOnDisk = new ArrayList<>();
        private int notOnDiskTotal;
        private int onDiskNotInPlex;
        private final List<String> collections = new ArrayList<>();
        private int collectionsTotal;

        private static void cappedAdd(List<String> list, String item) {
            if (list.size() < PlexDtos.LIST_CAP) {
                list.add(item);
            }
        }

        private PlexImportReport report() {
            return new PlexImportReport(plexFilms, paired, merged, editionsMoved, metadataApplied, postersStored,
                backdropsStored, artworkFailures, invalidArtPaths, tmdbUnavailable, watchedImported,
                resumePointsImported, keptNewer, failures, failedFilms, notOnDisk, notOnDiskTotal, onDiskNotInPlex,
                collections, collectionsTotal);
        }
    }

    private PlexImportReport importAll(Instant importTime) {
        Tally tally = new Tally();
        List<PlexFilm> plexFilms = new ArrayList<>();
        for (String section : plex.movieSections()) {
            plexFilms.addAll(plex.films(section));
            for (String name : plex.collections(section)) {
                tally.collectionsTotal++;
                Tally.cappedAdd(tally.collections, name);
            }
        }
        tally.plexFilms = plexFilms.size();

        Map<String, Known> known = new HashMap<>();
        for (Known k : ingest.known().values()) {
            known.put(PlexRules.nfc(k.path()), k);
        }
        Set<Long> claimed = new HashSet<>();
        List<Pairing> pairings = new ArrayList<>();
        for (PlexFilm film : plexFilms) {
            List<Long> ids = new ArrayList<>();
            for (String file : film.files()) {
                Known match = PlexRules.relativePath(prefix, file).map(known::get).orElse(null);
                if (match != null && claimed.add(match.id())) {
                    ids.add(match.id());
                }
            }
            if (ids.isEmpty()) {
                tally.notOnDiskTotal++;
                Tally.cappedAdd(tally.notOnDisk, film.title() == null ? "Untitled" : film.title());
            } else {
                pairings.add(new Pairing(film, ids));
            }
        }
        if (pairings.isEmpty()) {
            throw new PlexException("No Plex file matches a file in the library; check PLEX_PATH_PREFIX", null);
        }
        tally.paired = pairings.size();
        tally.onDiskNotInPlex = (int) known.values().stream()
            .filter(k -> k.presence() == Presence.PRESENT && !claimed.contains(k.id())).count();

        int processed = 0;
        for (Pairing pairing : pairings) {
            progress.set(new PlexProgress(processed, pairings.size(), pairing.film().title()));
            importFilm(pairing, tally, importTime);
            processed++;
        }
        // Files written for a film whose transaction then rolled back are not referenced by anything: drop them.
        artwork.deleteUnreferenced(writer.storedArtworkPaths());
        return tally.report();
    }

    private void importFilm(Pairing pairing, Tally tally, Instant importTime) {
        PlexFilm film = pairing.film();
        try {
            Preflight pre = writer.preflight(pairing.mediaFileIds(), film.tmdbId());
            MovieDetails movie = pre.needsTmdb() ? movie(film.tmdbId(), tally) : null;
            String poster = pre.hasPoster() ? null : download(film.thumb(), POSTER_WIDTH, POSTER_HEIGHT, tally);
            String backdrop = pre.hasBackdrop() ? null
                : download(film.art(), BACKDROP_WIDTH, BACKDROP_HEIGHT, tally);
            Applied applied = writer.apply(new FilmWrite(film, pairing.mediaFileIds(), movie, poster, backdrop,
                importTime));
            tally.postersStored += poster == null ? 0 : 1;
            tally.backdropsStored += backdrop == null ? 0 : 1;
            tally.merged += applied.merged();
            tally.editionsMoved += applied.editionsMoved();
            tally.metadataApplied++;
            tally.watchedImported += applied.watched();
            tally.resumePointsImported += applied.resumePoints();
            tally.keptNewer += applied.keptNewer();
        } catch (RuntimeException e) {
            tally.failures++;
            Tally.cappedAdd(tally.failedFilms, film.title() == null ? "Untitled" : film.title());
            LOG.warn("Could not import a Plex film: {}", e.getClass().getSimpleName());
        }
    }

    /** TMDB's film by id, or null when TMDB is off, down or does not know it (the film keeps Plex's metadata). */
    private MovieDetails movie(Long tmdbId, Tally tally) {
        if (!tmdb.enabled()) {
            return null;
        }
        try {
            return tmdb.movie(tmdbId).orElse(null);
        } catch (TmdbUnavailableException e) {
            tally.tmdbUnavailable++;
            return null;
        }
    }

    /** Downloads one image through Plex's resizer into the store; null when there is none or it failed. */
    private String download(String path, int width, int height, Tally tally) {
        if (path == null) {
            return null;
        }
        if (!PlexRules.isArtPath(path)) {
            tally.invalidArtPaths++;
            return null;
        }
        try {
            PlexImage image = plex.image(path, width, height);
            return artwork.store(image.bytes(), image.contentType());
        } catch (PlexException | IllegalArgumentException | java.io.UncheckedIOException e) {
            tally.artworkFailures++;
            LOG.warn("Could not store a Plex image: {}", e.getMessage());
            return null;
        }
    }
}
