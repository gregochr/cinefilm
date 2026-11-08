package gregochr.cinefilm.library;

import gregochr.cinefilm.film.MediaFileRepository.Known;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryIngest.Pending;
import gregochr.cinefilm.library.LibraryScanner.Found;
import gregochr.cinefilm.library.LibraryScanner.FoundFile;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Progress> progress = new AtomicReference<>();

    /** Creates the service; the executor runs scans in the background. */
    public ScanService(LibraryRoot root, LibraryScanner scanner, FfprobeService ffprobe, TmdbApi tmdb,
                       TmdbMatcher matcher, LibraryIngest ingest,
                       @Qualifier("scanExecutor") Executor scanExecutor, Clock clock) {
        this.root = root;
        this.scanner = scanner;
        this.ffprobe = ffprobe;
        this.tmdb = tmdb;
        this.matcher = matcher;
        this.ingest = ingest;
        this.executor = scanExecutor;
        this.clock = clock;
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
        return running.get();
    }

    /** Progress of the running scan, or null when idle. */
    public Progress progress() {
        return running.get() ? progress.get() : null;
    }

    /**
     * Starts a scan in the background.
     *
     * @param trigger what asked for it: {@code startup}, {@code nightly} or {@code admin}
     * @throws ScanAlreadyRunningException when a scan is already running
     */
    public void start(String trigger) {
        if (!running.compareAndSet(false, true)) {
            throw new ScanAlreadyRunningException();
        }
        progress.set(new Progress(0, 0, null));
        try {
            executor.execute(() -> {
                try {
                    execute(trigger);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
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
    }

    private record Counts(int seen, int added, int missing, String error) {
    }

    /** Mutable state of one scan. */
    private static final class Run {
        private final Map<String, Known> known;
        private final Set<Long> claimed = new HashSet<>();
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
            if (k.presence() == Presence.PRESENT && !onDisk.contains(k.path()) && !run.claimed.contains(k.id())) {
                vanished.add(k.id());
            }
        }
        ingest.markMissing(vanished);
        retryPending(run);
        String error = found.rejected() > 0
            ? found.rejected() + " files outside the library folder were refused" : null;
        if (run.failed > 0) {
            error = run.failed + " files could not be processed" + (error == null ? "" : "; " + error);
        }
        return new Counts(found.files().size(), run.added, vanished.size(), error);
    }

    private void handle(FoundFile file, Run run, Set<String> onDisk) {
        Known existing = run.known.get(file.path());
        if (existing != null) {
            boolean unchanged = existing.sizeBytes() != null && existing.sizeBytes() == file.sizeBytes()
                && existing.modifiedAt() != null
                && existing.modifiedAt().toEpochMilli() == file.modifiedAt().toEpochMilli();
            if (unchanged && existing.playability() != Playability.UNREADABLE) {
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
            .filter(k -> !onDisk.contains(k.path()) && !run.claimed.contains(k.id())
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
        TitleGuess guess = guess(file, probe);
        MatchOutcome outcome = playability == Playability.PLAYABLE ? lookup(run, guess, probe) : null;
        ingest.addNew(file, playability, probe, guess, outcome);
        run.added++;
    }

    private MatchOutcome lookup(Run run, TitleGuess guess, ProbeResult probe) {
        if (run.tmdbDown || !tmdb.enabled()) {
            return null;
        }
        MatchOutcome outcome = matcher.match(guess, probe.durationMs());
        if (outcome.status() == Status.UNAVAILABLE) {
            run.tmdbDown = true;
            return null;
        }
        return outcome;
    }

    private void retryPending(Run run) {
        if (run.tmdbDown || !tmdb.enabled()) {
            return;
        }
        for (Pending pending : ingest.pending()) {
            MatchOutcome outcome = matcher.match(new TitleGuess(pending.title(), pending.year()),
                pending.runtimeMs());
            if (outcome.status() == Status.UNAVAILABLE) {
                return;
            }
            ingest.applyOutcome(pending.filmId(), outcome);
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

    private static TitleGuess guess(FoundFile file, ProbeResult probe) {
        return TitleGuess.of(probe == null ? null : probe.tags().title(), file.fileName(),
            probe == null ? null : probe.tags().year());
    }
}
