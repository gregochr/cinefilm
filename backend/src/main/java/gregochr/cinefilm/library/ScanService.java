package gregochr.cinefilm.library;

import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.FileNameParser.ParsedName;
import gregochr.cinefilm.library.LibraryIngest.Pending;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
import gregochr.cinefilm.subtitle.SidecarSync;
import gregochr.cinefilm.tmdb.MatchOutcome;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbMatcher;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Scans the library folder. Only one scan runs at a time; a scan started while another runs is refused. A scan adds
 * new files, updates changed ones, follows renames, marks vanished files missing (never deleting them or their watch
 * history), and retries films whose TMDB lookup failed earlier. A TMDB outage or one unreadable file never fails the
 * scan.
 */
@Service
public class ScanService {

    private static final Logger LOG = LoggerFactory.getLogger(ScanService.class);

    private final LibraryRoot root;
    private final LibraryScanner scanner;
    private final FfprobeService ffprobe;
    private final TmdbApi tmdb;
    private final TmdbMatcher matcher;
    private final LibraryIngest ingest;
    private final Executor executor;
    private final Clock clock;
    private final LibraryLock lock;
    private final ApplicationEventPublisher events;
    private final LibraryGrouping grouping;
    private final SidecarSync sidecars;
    private final AtomicReference<Progress> progress = new AtomicReference<>();

    /** Creates the service; the executor runs scans in the background, the lock keeps scans and imports apart. */
    public ScanService(LibraryRoot root, LibraryScanner scanner, FfprobeService ffprobe, TmdbApi tmdb,
                       TmdbMatcher matcher, LibraryIngest ingest,
                       @Qualifier("scanExecutor") Executor scanExecutor, Clock clock, LibraryLock lock,
                       ApplicationEventPublisher events, LibraryGrouping grouping, SidecarSync sidecars) {
        this.root = root;
        this.scanner = scanner;
        this.ffprobe = ffprobe;
        this.tmdb = tmdb;
        this.matcher = matcher;
        this.ingest = ingest;
        this.executor = scanExecutor;
        this.clock = clock;
        this.lock = lock;
        this.events = events;
        this.grouping = grouping;
        this.sidecars = sidecars;
    }

    /**
     * Live progress of the running scan.
     *
     * @param total files found on disk (0 until the walk finishes)
     * @param processed files handled so far
     * @param current the file being handled, or null
     */
    public record Progress(int total, int processed, String current) {
    }

    /** True while a scan is running. */
    public boolean isRunning() {
        return lock.heldBy(LibraryLock.SCAN);
    }

    /** Progress of the running scan, or null when idle. */
    public Progress progress() {
        return isRunning() ? progress.get() : null;
    }

    /**
     * Starts a scan in the background.
     *
     * @param trigger what asked for it: {@code startup}, {@code nightly} or {@code admin}
     * @throws ScanAlreadyRunningException when a scan or a Plex import is already running
     */
    public void start(String trigger) {
        if (!lock.tryAcquire(LibraryLock.SCAN)) {
            throw lock.heldBy(LibraryLock.SCAN) ? new ScanAlreadyRunningException()
                : lock.heldBy(LibraryLock.IMPORT) ? new ScanAlreadyRunningException("A Plex import is running")
                : lock.heldBy(LibraryLock.EDIT) ? new ScanAlreadyRunningException("Editions are being edited")
                : lock.heldBy(LibraryLock.SUBTITLE) ? new ScanAlreadyRunningException("A subtitle is being changed")
                : lock.heldBy(LibraryLock.CREDITS) ? new ScanAlreadyRunningException("Credits are being saved")
                : lock.heldBy(LibraryLock.PLEX_CREDITS)
                    ? new ScanAlreadyRunningException("Credits from Plex are being saved")
                : new ScanAlreadyRunningException("A match is being fixed");
        }
        progress.set(new Progress(0, 0, null));
        try {
            executor.execute(() -> {
                try {
                    execute(trigger);
                } finally {
                    lock.release();
                }
            });
        } catch (RuntimeException e) {
            lock.release();
            throw e;
        }
    }

    private void execute(String trigger) {
        Long run = ingest.startRun(trigger, clock.instant());
        int seen = 0;
        int added = 0;
        int missing = 0;
        String error = null;
        try {
            Counts counts = scan();
            seen = counts.seen();
            added = counts.added();
            missing = counts.missing();
            error = counts.error();
        } catch (UncheckedIOException e) {
            error = "Cannot read the library folder";
            LOG.warn("Scan could not read the library folder: {}", e.getMessage());
        } catch (RuntimeException e) {
            error = "Scan failed: " + e.getClass().getSimpleName();
            LOG.error("Scan failed", e);
        }
        ingest.finishRun(run, clock.instant(), seen, added, missing, error);
        LOG.info("Scan finished: {} files, {} new, {} missing, note: {}", seen, added, missing, error);
        events.publishEvent(new ScanFinished(trigger));
    }

    private record Counts(int seen, int added, int missing, String error) {
    }

    /** Mutable state of one scan. */
    private static final class Run {
        private final Map<String, Known> known;
        private final Set<Long> claimed = new HashSet<>();
        private Found found;
        private boolean tmdbDown;
        private int added;
        private int failed;

        private Run(Map<String, Known> known) {
            this.known = known;
        }
    }

    private Counts scan() {
        Found found = scanner.scan();
        Map<String, Known> known = ingest.known();
        Set<String> onDisk = new HashSet<>();
        found.files().forEach(f -> onDisk.add(f.path()));
        boolean hadFiles = known.values().stream().anyMatch(k -> k.presence() == Presence.PRESENT);
        if (found.files().isEmpty() && hadFiles) {
            return new Counts(0, 0, 0, "The library folder is empty or unavailable; nothing was marked missing");
        }
        Run run = new Run(known);
        run.found = found;
        AtomicInteger processed = new AtomicInteger();
        for (FoundFile file : found.files()) {
            progress.set(new Progress(found.files().size(), processed.get(), file.fileName()));
            try {
                handle(file, run, onDisk);
            } catch (RuntimeException e) {
                run.failed++;
                LOG.warn("Could not process {}: {}", file.path(), e.getClass().getSimpleName());
            }
            processed.incrementAndGet();
        }
        List<Long> vanished = new ArrayList<>();
        for (Known k : known.values()) {
            if (k.presence() == Presence.PRESENT && !onDisk.contains(k.path()) && !run.claimed.contains(k.id())
                && !found.isUnder(k.path())) {
                vanished.add(k.id());
            }
        }
        ingest.markMissing(vanished);
        groupLibrary(run);
        syncSidecars(run);
        retryPending(run);
        completeCollections(run);
        refreshDefaults(run);
        List<String> notes = new ArrayList<>();
        if (run.failed > 0) {
            notes.add(run.failed + " files could not be processed");
        }
        if (found.rejected() > 0) {
            notes.add(found.rejected() + " files outside the library folder were refused");
        }
        if (!found.unreadable().isEmpty()) {
            notes.add(found.unreadable().size() + " folders could not be read; their films were left as they were");
        }
        return new Counts(found.files().size(), run.added, vanished.size(),
            notes.isEmpty() ? null : String.join("; ", notes));
    }

    private void handle(FoundFile file, Run run, Set<String> onDisk) {
        Known existing = run.known.get(file.path());
        if (existing != null) {
            boolean unchanged = existing.sizeBytes() != null && existing.sizeBytes() == file.sizeBytes()
                && existing.modifiedAt() != null
                && existing.modifiedAt().toEpochMilli() == file.modifiedAt().toEpochMilli();
            // A null pixel format or video duration means the file was last probed before that column existed:
            // probe it once more (the update stores a value, or an empty marker, so this happens once per file).
            if (unchanged && existing.playability() != Playability.UNREADABLE && existing.pixelFormat() != null
                && existing.videoDurationMs() != null) {
                if (existing.presence() == Presence.MISSING) {
                    ingest.markPresent(existing.id());
                }
                return;
            }
            ProbeResult probe = probe(file);
            ingest.refresh(existing.id(), file, classify(file, probe), probe);
            return;
        }
        Optional<Known> moved = run.known.values().stream()
            .filter(k -> !onDisk.contains(k.path()) && !run.claimed.contains(k.id()) && !run.found.isUnder(k.path())
                && k.sizeBytes() != null && k.sizeBytes() == file.sizeBytes() && k.modifiedAt() != null
                && k.modifiedAt().toEpochMilli() == file.modifiedAt().toEpochMilli())
            .findFirst();
        if (moved.isPresent()) {
            run.claimed.add(moved.get().id());
            ingest.repoint(moved.get().id(), file);
            return;
        }
        ProbeResult probe = probe(file);
        Playability playability = classify(file, probe);
        ParsedName parsed = FileNameParser.parse(file.path());
        TitleGuess guess = guess(file, probe, parsed);
        // Another part of an edition we already have is added to its film without asking TMDB; the grouping pass at
        // the end of the scan joins it to that edition.
        boolean lookup = playability == Playability.PLAYABLE && !ingest.isPartOfKnownEdition(file.path());
        MatchOutcome outcome = lookup ? lookup(run, guess, parsed, probe) : null;
        ingest.addNew(file, playability, probe, guess, outcome);
        run.added++;
    }

    /**
     * Looks a file up on TMDB: by the id its name carries when it has one, else by title and year. A part-numbered
     * file is searched with duration 0, since its length says nothing about the film's runtime.
     */
    private MatchOutcome lookup(Run run, TitleGuess guess, ParsedName parsed, ProbeResult probe) {
        if (run.tmdbDown || !tmdb.enabled()) {
            return null;
        }
        MatchOutcome outcome = null;
        if (parsed.tmdbId() != null || parsed.imdbId() != null) {
            outcome = matcher.matchTagged(parsed.tmdbId(), parsed.imdbId()).orElse(null);
        }
        if (outcome == null) {
            outcome = matcher.match(guess, parsed.partTag() == null ? probe.durationMs() : 0);
        }
        if (outcome.status() == Status.UNAVAILABLE) {
            run.tmdbDown = true;
            return null;
        }
        return outcome;
    }

    /** Repair, then auto-resolve: neither needs TMDB, and a failure leaves the work for the next scan. */
    private void groupLibrary(Run run) {
        try {
            grouping.repair();
            grouping.group();
        } catch (RuntimeException e) {
            run.failed++;
            LOG.warn("Could not group the library: {}", e.getClass().getSimpleName());
        }
    }

    /** Forced subtitle files: record what the walk found, then match them to films on every scan. */
    private void syncSidecars(Run run) {
        try {
            sidecars.sync(run.found);
        } catch (RuntimeException e) {
            run.failed++;
            LOG.warn("Could not sync the forced subtitle files: {}", e.getClass().getSimpleName());
        }
    }

    private void refreshDefaults(Run run) {
        try {
            grouping.refreshDefaults();
        } catch (RuntimeException e) {
            run.failed++;
            LOG.warn("Could not refresh the default editions: {}", e.getClass().getSimpleName());
        }
    }

    private void retryPending(Run run) {
        if (run.tmdbDown || !tmdb.enabled()) {
            return;
        }
        for (Pending pending : ingest.pending()) {
            MatchOutcome outcome = null;
            if (pending.tmdbId() != null || pending.imdbId() != null) {
                outcome = matcher.matchTagged(pending.tmdbId(), pending.imdbId()).orElse(null);
            }
            if (outcome == null) {
                outcome = matcher.match(new TitleGuess(pending.title(), pending.year()), pending.runtimeMs());
            }
            if (outcome.status() == Status.UNAVAILABLE) {
                run.tmdbDown = true;
                return;
            }
            try {
                ingest.applyOutcome(pending.filmId(), outcome);
            } catch (RuntimeException e) {
                run.failed++;
                LOG.warn("Could not apply the TMDB match for film {}: {}", pending.filmId(),
                    e.getClass().getSimpleName());
            }
        }
    }

    private void completeCollections(Run run) {
        if (run.tmdbDown || !tmdb.enabled()) {
            return;
        }
        try {
            ingest.completeCollections();
        } catch (RuntimeException e) {
            LOG.warn("Could not complete collections: {}", e.getClass().getSimpleName());
        }
    }

    private ProbeResult probe(FoundFile file) {
        try {
            return ffprobe.probe(root.resolveFile(file.path())
                .orElseThrow(() -> new ProbeException("The file is no longer available", null)));
        } catch (ProbeException e) {
            LOG.warn("Could not probe {}: {}", file.path(), e.getMessage());
            return null;
        }
    }

    private static Playability classify(FoundFile file, ProbeResult probe) {
        if (probe != null && probe.protectedContent()) {
            return Playability.PROTECTED;
        }
        if (file.needsConversion()) {
            return Playability.NEEDS_CONVERSION;
        }
        return probe == null ? Playability.UNREADABLE : Playability.PLAYABLE;
    }

    private static TitleGuess guess(FoundFile file, ProbeResult probe, ParsedName parsed) {
        return TitleGuess.of(probe == null ? null : probe.tags().title(), file.fileName(),
            probe == null ? null : probe.tags().year(), parsed);
    }
}
