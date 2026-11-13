package gregochr.cinefilm.plex;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.library.LibraryIngest;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.plex.PlexCreditsDtos.LastCreditsImport;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsProgress;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsReport;
import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsStatus;
import gregochr.cinefilm.plex.PlexCreditsDtos.SkipCount;
import gregochr.cinefilm.plex.PlexCreditsRules.Copy;
import gregochr.cinefilm.plex.PlexCreditsRules.Decision;
import gregochr.cinefilm.plex.PlexCreditsRules.Row;
import gregochr.cinefilm.plex.PlexModels.PlexFilm;
import gregochr.cinefilm.plex.PlexModels.PlexItemMarkers;
import gregochr.cinefilm.tmdb.Sleeper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one-off import of Plex's credits markers (phase 2c): pairs every Plex film with CineFilm's files exactly as the
 * Plex import does, asks Plex for each matched film's markers, one request at a time, and stores a marker for each
 * file it can trust (see {@link PlexCreditsRules}). Read-only towards Plex (GET requests only) and it applies
 * nothing: no edition, player or watched rule reads the rows.
 *
 * <p>The roughly 1,100 requests are made without the {@link LibraryLock}, so a scan is never kept waiting; the rows are
 * then written in one short step under the lock, each only if its file still has the fingerprint it had when Plex was
 * asked about it. Running it again replaces rows; it never deletes one.
 */
@Service
public class PlexCreditsImportService {

    private static final Logger LOG = LoggerFactory.getLogger(PlexCreditsImportService.class);
    /** Plex failing this many items in a row is Plex being down: the import stops instead of waiting on each. */
    static final int MAX_CONSECUTIVE_FAILURES = 5;
    /** How often the write step tries for the lock, and how long it waits between tries (10 minutes in all). */
    static final int LOCK_ATTEMPTS = 120;
    static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    private final PlexApi plex;
    private final LibraryIngest ingest;
    private final MediaFileRepository files;
    private final PlexCreditsMarkerRepository markers;
    private final LibraryLock lock;
    private final Executor executor;
    private final Clock clock;
    private final Sleeper sleeper;
    private final String prefix;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<PlexCreditsProgress> progress = new AtomicReference<>();
    private final AtomicReference<LastCreditsImport> last = new AtomicReference<>();

    /** Creates the service; the executor runs the import in the background. */
    public PlexCreditsImportService(PlexApi plex, LibraryIngest ingest, MediaFileRepository files,
                                    PlexCreditsMarkerRepository markers, LibraryLock lock,
                                    @Qualifier("plexCreditsExecutor") Executor executor, Clock clock,
                                    Sleeper sleeper, @Value("${cinefilm.plex.path-prefix}") String pathPrefix,
                                    PlatformTransactionManager transactions) {
        this.plex = plex;
        this.ingest = ingest;
        this.files = files;
        this.markers = markers;
        this.lock = lock;
        this.executor = executor;
        this.clock = clock;
        this.sleeper = sleeper;
        this.prefix = PlexRules.prefix(pathPrefix);
        this.tx = new TransactionTemplate(transactions);
    }

    /** True while an import is running. */
    public boolean isRunning() {
        return running.get();
    }

    /** Whether an import runs (with its progress) and the last finished one. */
    public PlexCreditsStatus status() {
        boolean now = running.get();
        return new PlexCreditsStatus(now, now ? progress.get() : null, last.get());
    }

    /**
     * Starts an import in the background.
     *
     * @throws NotFoundException when Plex is not configured
     * @throws ConflictException when an import is already running, or the Plex import is (it holds the library lock
     *     for its whole run, far longer than the write step would wait)
     */
    public void start() {
        if (!plex.configured()) {
            throw new NotFoundException("Plex is not configured");
        }
        if (lock.heldBy(LibraryLock.IMPORT)) {
            throw new ConflictException("Import credits after the Plex import finishes.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new ConflictException("A credits import is already running.");
        }
        try {
            progress.set(new PlexCreditsProgress(0, 0));
            executor.execute(() -> {
                try {
                    execute();
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void execute() {
        Instant startedAt = clock.instant();
        PlexCreditsReport report = null;
        String error = null;
        try {
            report = importAll(startedAt);
        } catch (PlexException e) {
            error = e.getMessage();
            LOG.warn("Plex credits import stopped: {}", e.getMessage());
        } catch (RuntimeException e) {
            error = "Import failed: " + e.getClass().getSimpleName();
            LOG.error("Plex credits import failed", e);
        }
        last.set(new LastCreditsImport(startedAt, clock.instant(), error, report));
    }

    /** A Plex film and the CineFilm files its list entry paired with. */
    private record Asked(PlexFilm film, List<Long> fileIds) {
    }

    private PlexCreditsReport importAll(Instant importTime) {
        List<PlexFilm> plexFilms = new ArrayList<>();
        for (String section : plex.movieSections()) {
            plexFilms.addAll(plex.films(section));
        }
        Map<String, Known> known = new HashMap<>();
        for (Known k : ingest.known().values()) {
            known.put(PlexRules.nfc(k.path()), k);
        }
        Map<Long, Known> byId = new HashMap<>();
        known.values().forEach(k -> byId.put(k.id(), k));
        // The claimed set is built the way the Plex import builds it: every film, in list order.
        Set<Long> claimed = new HashSet<>();
        List<Asked> asked = new ArrayList<>();
        for (PlexFilm film : plexFilms) {
            List<Long> ids = PlexPairing.pair(film, prefix, known, claimed).media().stream()
                .flatMap(m -> m.fileIds().stream()).toList();
            if (!ids.isEmpty()) {
                asked.add(new Asked(film, ids));
            }
        }
        Map<Long, MediaFile> current = current(asked);
        Map<PlexCreditsSkip, Integer> skipped = new EnumMap<>(PlexCreditsSkip.class);
        List<Row> rows = new ArrayList<>();
        int unmatched = 0;
        int consecutiveFailures = 0;
        int processed = 0;
        for (Asked item : asked) {
            progress.set(new PlexCreditsProgress(processed++, asked.size()));
            PlexItemMarkers markersOfItem = fetch(item.film());
            consecutiveFailures = markersOfItem == null ? consecutiveFailures + 1 : 0;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw new PlexException("Plex stopped answering after " + MAX_CONSECUTIVE_FAILURES
                    + " films in a row; nothing was stored", null);
            }
            if (markersOfItem == null) {
                skipped.merge(PlexCreditsSkip.PLEX_UNAVAILABLE, 1, Integer::sum);
                continue;
            }
            List<Copy> copies = new ArrayList<>();
            for (Long id : item.fileIds()) {
                MediaFile file = current.get(id);
                if (file == null) {
                    skipped.merge(PlexCreditsSkip.FILE_CHANGED, 1, Integer::sum);
                } else {
                    copies.add(new Copy(id, byId.get(id).path(), file.fingerprint(), file.timelineDurationMs()));
                }
            }
            Decision decision = PlexCreditsRules.decide(markersOfItem, copies, prefix);
            rows.addAll(decision.rows());
            unmatched += decision.unmatched();
            decision.skipped().forEach((reason, count) -> skipped.merge(reason, count, Integer::sum));
        }
        progress.set(new PlexCreditsProgress(asked.size(), asked.size()));
        int imported = write(rows, importTime, skipped);
        return new PlexCreditsReport(plexFilms.size(), asked.size(), imported, unmatched, skippedCounts(skipped));
    }

    /** The files the paired films point at, as CineFilm has them now. */
    private Map<Long, MediaFile> current(List<Asked> asked) {
        List<Long> ids = asked.stream().flatMap(a -> a.fileIds().stream()).toList();
        return files.findAllById(ids).stream().collect(Collectors.toMap(MediaFile::getId, f -> f));
    }

    /** One item's markers, or null when Plex did not give a usable answer (the film is counted, the import goes on). */
    private PlexItemMarkers fetch(PlexFilm film) {
        try {
            return plex.itemMarkers(film.ratingKey());
        } catch (PlexException e) {
            LOG.warn("Could not read a Plex item's markers: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Writes the rows in one transaction under the library lock, waiting for the lock if a scan or an edit holds it.
     * A row is written only when its file still exists with the fingerprint it had when Plex was asked about it.
     */
    private int write(List<Row> rows, Instant importTime, Map<PlexCreditsSkip, Integer> skipped) {
        if (rows.isEmpty()) {
            return 0;
        }
        acquireLock();
        try {
            return tx.execute(status -> writeLocked(rows, importTime, skipped));
        } finally {
            lock.release();
        }
    }

    private void acquireLock() {
        String holder = "another job";
        for (int attempt = 0; attempt < LOCK_ATTEMPTS; attempt++) {
            if (lock.tryAcquire(LibraryLock.PLEX_CREDITS)) {
                return;
            }
            holder = Objects.requireNonNullElse(lock.holder(), holder);
            try {
                sleeper.sleep(LOCK_WAIT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlexException("Interrupted while waiting for the library lock; nothing was stored", e);
            }
        }
        throw new PlexException("The library stayed locked by \"" + holder + "\" for "
            + LOCK_WAIT.multipliedBy(LOCK_ATTEMPTS).toMinutes() + " minutes; nothing was stored, "
            + "run the import again", null);
    }

    private int writeLocked(List<Row> rows, Instant importTime, Map<PlexCreditsSkip, Integer> skipped) {
        List<Long> ids = rows.stream().map(Row::mediaFileId).toList();
        Map<Long, MediaFile> current = files.findAllById(ids).stream()
            .collect(Collectors.toMap(MediaFile::getId, f -> f));
        Map<Long, PlexCreditsMarker> existing = markers.findAllById(ids).stream()
            .collect(Collectors.toMap(PlexCreditsMarker::getMediaFileId, m -> m));
        List<PlexCreditsMarker> toSave = new ArrayList<>();
        for (Row row : rows) {
            MediaFile file = current.get(row.mediaFileId());
            if (file == null || !row.fingerprint().equals(file.fingerprint())) {
                skipped.merge(PlexCreditsSkip.FILE_CHANGED, 1, Integer::sum);
                continue;
            }
            PlexCreditsMarker marker = existing.getOrDefault(row.mediaFileId(), new PlexCreditsMarker());
            marker.setMediaFileId(row.mediaFileId());
            marker.setFingerprint(row.fingerprint());
            marker.setStartMs(row.startMs());
            marker.setImportedAt(importTime);
            toSave.add(marker);
        }
        markers.saveAll(toSave);
        return toSave.size();
    }

    private static List<SkipCount> skippedCounts(Map<PlexCreditsSkip, Integer> skipped) {
        List<SkipCount> counts = new ArrayList<>();
        for (PlexCreditsSkip reason : PlexCreditsSkip.values()) {
            int count = skipped.getOrDefault(reason, 0);
            if (count > 0) {
                counts.add(new SkipCount(reason.name(), reason.description(), count));
            }
        }
        return counts;
    }
}
