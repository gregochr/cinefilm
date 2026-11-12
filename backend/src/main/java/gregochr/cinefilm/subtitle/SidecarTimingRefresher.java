package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.subtitle.SidecarTimingService.FitJob;
import gregochr.cinefilm.subtitle.SidecarTimingService.ReferenceRead;
import gregochr.cinefilm.subtitle.SidecarTimingService.SaveOutcome;
import gregochr.cinefilm.transcode.SessionManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Checks the timing of the forced subtitle files playback serves against the full English subtitle track inside each
 * film's own media file ({@link TimingFit}), in the background, one film at a time and one run at a time: after a scan,
 * at start-up and daily. Off with {@code cinefilm.subtitles.timing-check.enabled=false}.
 *
 * <p>For each due film ({@link SidecarTimingService#dueFilmIds}) it first reads the embedded track of each film file
 * that has not been read as it is now ({@link EmbeddedCueReader}: minutes over SMB, run without any lock, and stored
 * at once so a file is read once), then fits each served sidecar and saves the result under the library lock, which
 * also sets the sidecar's offset when the fit is aligned and the owner has not said otherwise. Playback comes first:
 * the run stops, to be tried again, while any HLS session is encoding (checked before each film and each read), and
 * when the library lock is taken by a scan or an Admin edit. A read that fails (ffprobe missing, an error, a timeout)
 * is not stored and not tried again for {@value #BACKOFF_HOURS} hours. A request that arrives while a run is going
 * makes it go round once more. Nothing here throws to its caller.
 */
@Service
public class SidecarTimingRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarTimingRefresher.class);

    /** How long a file that could not be read is left alone, in hours. */
    static final int BACKOFF_HOURS = 6;
    /** How long a file that could not be read is left alone. */
    static final Duration BACKOFF = Duration.ofHours(BACKOFF_HOURS);

    private final SidecarTimingService service;
    private final EmbeddedCueReader reader;
    private final LibraryRoot root;
    private final SessionManager sessions;
    private final Clock clock;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean deferred = new AtomicBoolean();
    private final Map<Long, Instant> failedAt = new ConcurrentHashMap<>();

    /** Why a run ends early: playback is encoding, or the library lock is taken. Not an error. */
    private static final class Wait extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Wait(String message) {
            super(message, null, false, false);
        }
    }

    /** Creates the service; the executor is {@code timingCheckExecutor}. */
    public SidecarTimingRefresher(SidecarTimingService service, EmbeddedCueReader reader, LibraryRoot root,
                                  SessionManager sessions, Clock clock,
                                  @Qualifier("timingCheckExecutor") Executor executor,
                                  @Value("${cinefilm.subtitles.timing-check.enabled:true}") boolean enabled) {
        this.service = service;
        this.reader = reader;
        this.root = root;
        this.sessions = sessions;
        this.clock = clock;
        this.executor = executor;
        this.enabled = enabled;
    }

    /** True while a run is going. */
    public boolean isRunning() {
        return running.get();
    }

    /** Checks at start-up, and says so when the check is off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (enabled) {
            startDue();
        } else {
            LOG.info("Subtitle timing checks are off (cinefilm.subtitles.timing-check.enabled)");
        }
    }

    /** Checks after a library scan (a file may have been added, moved or changed). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /**
     * Asks for a check run in the background, unless the check is off. When one is already running the request is not
     * lost: the running one goes round again before it finishes.
     *
     * @return true when this call started a run; false when the check is off or a run was already going
     */
    public boolean startDue() {
        if (!enabled) {
            return false;
        }
        // Set before trying to take the run: a run that is just finishing re-checks it after giving the run up.
        pending.set(true);
        return launch();
    }

    /**
     * Asks for a run only when the last one stopped to let playback or an edit go first, so the work is picked up
     * again within the hour instead of waiting for the next day.
     */
    public void startIfDeferred() {
        if (deferred.get()) {
            startDue();
        }
    }

    private boolean launch() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(this::worker);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        return true;
    }

    private void worker() {
        try {
            while (pending.getAndSet(false)) {
                run();
            }
        } finally {
            running.set(false);
        }
        // A request that arrived after the last check but before the run was given up would otherwise be lost.
        if (pending.get()) {
            launch();
        }
    }

    /** One pass over the due films. */
    void run() {
        deferred.set(false);
        int[] counts = new int[4];
        try {
            for (Long filmId : service.dueFilmIds()) {
                if (sessions.isEncoding()) {
                    throw new Wait("a film is being transcoded");
                }
                try {
                    film(filmId, counts);
                } catch (Wait e) {
                    throw e;
                } catch (RuntimeException e) {
                    counts[3]++;
                    LOG.warn("Could not check the subtitle timing of film {}: {}", filmId,
                        e.getClass().getSimpleName());
                }
            }
        } catch (Wait e) {
            deferred.set(true);
            LOG.info("Subtitle timing checks wait: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Subtitle timing checks failed", e);
        }
        LOG.info("Subtitle timing checks finished: {} files read, {} fitted ({} offsets set), {} failed",
            counts[0], counts[1], counts[2], counts[3]);
    }

    /** Reads what the film's served sidecars need, then fits them. */
    private void film(Long filmId, int[] counts) {
        for (ReferenceRead read : service.readsFor(filmId)) {
            if (sessions.isEncoding()) {
                throw new Wait("a film is being transcoded");
            }
            Instant failed = failedAt.get(read.mediaFileId());
            if (failed != null && clock.instant().isBefore(failed.plus(BACKOFF))) {
                continue;
            }
            try {
                Path file = root.resolveFile(read.path())
                    .orElseThrow(() -> new EmbeddedCueReader.ReadException("The film file cannot be found"));
                EmbeddedCueReader.Reading reading = reader.read(file);
                failedAt.remove(read.mediaFileId());
                SaveOutcome saved = service.saveReference(read, reading, clock.instant());
                if (saved == SaveOutcome.BUSY) {
                    throw new Wait("a scan or an edit is using the library");
                }
                if (saved == SaveOutcome.SAVED) {
                    counts[0]++;
                }
            } catch (EmbeddedCueReader.ReadException e) {
                failedAt.put(read.mediaFileId(), clock.instant());
                counts[3]++;
                LOG.warn("Could not read the embedded subtitles of film file {}: {}", read.mediaFileId(),
                    e.getMessage());
            }
        }
        for (FitJob job : service.fitJobs(filmId)) {
            TimingFit.Result result = job.noReference() != null
                ? TimingFit.noReference(job.servedStarts().size(), job.noReference())
                : TimingFit.fit(job.servedStarts(), job.referenceStarts());
            SaveOutcome outcome = service.save(job, result, clock.instant());
            if (outcome == SaveOutcome.BUSY) {
                throw new Wait("a scan or an edit is using the library");
            }
            if (outcome != SaveOutcome.DROPPED) {
                counts[1]++;
            }
            if (outcome == SaveOutcome.APPLIED) {
                counts[2]++;
            }
        }
    }
}
