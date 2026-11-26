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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
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
 * makes it go round once more. A pass that fitted something publishes {@link SidecarTimingFinished}, and a
 * {@link SubtitleDownloadsFinished} starts a pass. Nothing here throws to its caller.
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
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean deferred = new AtomicBoolean();
    private final ReadFailures failures;

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
                                  @Value("${cinefilm.subtitles.timing-check.enabled:true}") boolean enabled,
                                  ApplicationEventPublisher events, ReadFailures failures) {
        this.service = service;
        this.reader = reader;
        this.root = root;
        this.sessions = sessions;
        this.clock = clock;
        this.executor = executor;
        this.enabled = enabled;
        this.events = events;
        this.failures = failures;
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

    /** Fits again after a download was added (it needs its timing fitted to be proven). */
    @EventListener
    public void onDownloadsFinished(SubtitleDownloadsFinished event) {
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

    /**
     * The re-check after the retime job rewrote or restored cues (docs/engineering/subtitle-frame-rate-plan.md, "The
     * re-check runs at once"): reads and fits one film now, on the calling thread (the job's, which is this
     * service's own executor), so it never overlaps a pass. When a pass is already booked or going, that pass is asked
     * to go round once more and this returns at once: it fits the same due sidecars.
     *
     * @param filmId the film
     * @return true when the film was fitted, or is left to a pass that will; false when a scan, an edit or an
     *     encoding session stopped it (try again)
     */
    public boolean fitFilm(Long filmId) {
        if (!enabled) {
            return true;
        }
        if (!running.compareAndSet(false, true)) {
            pending.set(true);
            return true;
        }
        boolean settled = true;
        try {
            if (sessions.isEncoding()) {
                throw new Wait("a film is being transcoded");
            }
            int[] counts = new int[4];
            film(filmId, counts);
            LOG.info("Subtitle timing of film {} after a retime: {} files read, {} fitted ({} offsets set), "
                + "{} failed", filmId, counts[0], counts[1], counts[2], counts[3]);
            if (counts[1] > 0) {
                announce();
            }
        } catch (Wait e) {
            deferred.set(true);
            settled = false;
            LOG.info("Subtitle timing of film {} waits: {}", filmId, e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Could not check the subtitle timing of film {}: {}", filmId, e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
        if (pending.get()) {
            launch();
        }
        return settled;
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
        if (counts[1] > 0) {
            announce();
        }
    }

    /** Says something was fitted, so a download that could not be proven by timing can be compared instead. */
    private void announce() {
        try {
            events.publishEvent(new SidecarTimingFinished());
        } catch (RuntimeException e) {
            LOG.warn("A listener of the subtitle timing check failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Reads what the film's served sidecars need, then fits them. */
    private void film(Long filmId, int[] counts) {
        for (ReferenceRead read : service.readsFor(filmId)) {
            if (sessions.isEncoding()) {
                throw new Wait("a film is being transcoded");
            }
            if (failures.backedOff(read.mediaFileId(), read.fingerprint(), clock.instant(), BACKOFF)) {
                continue;
            }
            Path file = root.resolveFile(read.path()).orElse(null);
            if (file == null) {
                // Not a failed read: a mount outage must not count towards giving the file up (ReadFailures).
                counts[3]++;
                LOG.warn("Could not read the embedded subtitles of film file {}: The film file cannot be found",
                    read.mediaFileId());
                continue;
            }
            try {
                EmbeddedCueReader.Reading reading = reader.read(file);
                failures.clear(read.mediaFileId());
                SaveOutcome saved = service.saveReference(read, reading, clock.instant());
                if (saved == SaveOutcome.BUSY) {
                    throw new Wait("a scan or an edit is using the library");
                }
                if (saved == SaveOutcome.SAVED) {
                    counts[0]++;
                }
            } catch (EmbeddedCueReader.ReadException e) {
                failures.record(read.mediaFileId(), read.fingerprint(), clock.instant());
                counts[3]++;
                LOG.warn("Could not read the embedded subtitles of film file {}: {}", read.mediaFileId(),
                    e.getMessage());
            }
        }
        // References stored before their last-cue end was kept cannot count until it is filled in (once per file).
        if (service.backfillLastCueEnds(filmId) == SaveOutcome.BUSY) {
            throw new Wait("a scan or an edit is using the library");
        }
        for (FitJob job : service.fitJobs(filmId)) {
            SaveOutcome outcome;
            if (!job.osReferences().isEmpty()) {
                // No track of its own: the best fit to the stored OpenSubtitles files, shown but never proof.
                DownloadedFit.Choice choice = DownloadedFit.choose(job.servedStarts(), job.osReferences());
                outcome = service.save(job, choice.result(), choice.reference().osFileId(), clock.instant());
            } else {
                TimingFit.Result result = job.noReference() != null
                    ? TimingFit.noReference(job.servedStarts().size(), job.noReference())
                    : TimingFit.fit(job.servedStarts(), job.referenceStarts());
                outcome = service.save(job, result, clock.instant());
            }
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
