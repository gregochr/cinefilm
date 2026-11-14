package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsDetectionService.SaveOutcome;
import gregochr.cinefilm.library.LibraryRoot;
import gregochr.cinefilm.library.ScanFinished;
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
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Works out where each film's end credits start, in the background, one film at a time and one run at a time: at
 * start-up and after every library scan (so every new film is analysed after the scan that finds it, the nightly scan
 * included). Off with {@code cinefilm.credits.enabled=false}. It stores what it finds ({@link CreditsDetection}) and
 * applies nothing.
 *
 * <p>Playback comes first. The run stops, to be tried again at the next trigger or the hourly nudge, when any HLS
 * session is encoding or playback progress was reported in the last {@value PlaybackGuard#QUIET_MINUTES} minutes
 * (direct play reads over SMB too; see {@link PlaybackGuard}); that is checked before each film and before every
 * ffmpeg call, and a file stopped part-way is abandoned and its frames deleted. A scan or an Admin edit holding the
 * library lock when a result is saved stops the run too. A file that fails (ffmpeg missing, an error, a timeout) is
 * not stored and not tried again for {@value #BACKOFF_HOURS} hours unless it changes. A request that arrives while a
 * run is going makes it go round once more. Nothing here throws to its caller.
 */
@Service
public class CreditsRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(CreditsRefresher.class);

    /** How long a file that could not be read is left alone, in hours. */
    static final int BACKOFF_HOURS = 6;
    /** How long a file that could not be read is left alone. */
    static final Duration BACKOFF = Duration.ofHours(BACKOFF_HOURS);
    /** The order of the start-up listener: before the credits dataset job's, which uses the directory it clears. */
    public static final int STARTUP_ORDER = 1;

    private final CreditsDetectionService service;
    private final CreditsFrameReader reader;
    private final LibraryRoot root;
    private final PlaybackGuard guard;
    private final Clock clock;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean deferred = new AtomicBoolean();
    private final Map<Long, Failure> failedAt = new ConcurrentHashMap<>();

    /** A file that failed: of which version, and when. A changed file is not held back by it. */
    private record Failure(String fingerprint, Instant at) {
    }

    /** Why a run ends early: playback needs the machine, or the library lock is taken. Not an error. */
    private static final class Wait extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Wait(String message) {
            super(message, null, false, false);
        }
    }

    /** Creates the service; the executor is {@code creditsExecutor}. */
    public CreditsRefresher(CreditsDetectionService service, CreditsFrameReader reader, LibraryRoot root,
                            PlaybackGuard guard, Clock clock,
                            @Qualifier("creditsExecutor") Executor executor,
                            @Value("${cinefilm.credits.enabled:true}") boolean enabled) {
        this.service = service;
        this.reader = reader;
        this.root = root;
        this.guard = guard;
        this.clock = clock;
        this.executor = executor;
        this.enabled = enabled;
    }

    /** True while a run is going. */
    public boolean isRunning() {
        return running.get();
    }

    /** Clears what a killed process left behind and starts a run at start-up, or says the analysis is off. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(STARTUP_ORDER)
    public void onStartup() {
        if (enabled) {
            reader.deleteLeftovers();
            startDue();
        } else {
            LOG.info("Credits detection is off (cinefilm.credits.enabled)");
        }
    }

    /** Analyses after a library scan (a film may have been added, moved or changed). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /**
     * Asks for a run in the background, unless the analysis is off. When one is already running the request is not
     * lost: the running one goes round again before it finishes.
     *
     * @return true when this call started a run; false when the analysis is off or a run was already going
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
     * How many of the files due for analysis are not held back by the failure back-off: the work the refresher would
     * start now. The credits dataset job yields while it is above 0.
     *
     * @return 0 when the analysis is off; 1 when the due files cannot be listed (so the caller yields); otherwise the
     *     count
     */
    public int dueNotHeldBack() {
        if (!enabled) {
            return 0;
        }
        try {
            return (int) service.dueFiles().stream().filter(due -> !heldBack(due)).count();
        } catch (RuntimeException e) {
            LOG.warn("Could not count the due credits files: {}",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return 1;
        }
    }

    /** Asks for a run only when the last one stopped to let playback or an edit go first. */
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

    /** One pass over the due files. */
    void run() {
        deferred.set(false);
        int analysed = 0;
        int failed = 0;
        try {
            for (DueFile due : service.dueFiles()) {
                guard.check();
                if (heldBack(due)) {
                    continue;
                }
                try {
                    analyse(due);
                    analysed++;
                } catch (Wait | PlaybackGuard.Busy e) {
                    throw e;
                } catch (RuntimeException e) {
                    failedAt.put(due.mediaFileId(), new Failure(due.fingerprint(), clock.instant()));
                    failed++;
                    LOG.warn("Could not analyse the credits of {} (file {}): {}", due.title(), due.mediaFileId(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
        } catch (Wait | PlaybackGuard.Busy e) {
            deferred.set(true);
            LOG.info("Credits detection waits: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Credits detection failed", e);
        }
        LOG.info("Credits detection finished: {} files analysed, {} failed", analysed, failed);
    }

    /** True while the file failed as it is now less than {@link #BACKOFF} ago. */
    private boolean heldBack(DueFile due) {
        Failure failure = failedAt.get(due.mediaFileId());
        return failure != null && failure.fingerprint().equals(due.fingerprint())
            && clock.instant().isBefore(failure.at().plus(BACKOFF));
    }

    private void analyse(DueFile due) {
        Instant started = clock.instant();
        Path file = root.resolveFile(due.path())
            .orElseThrow(() -> new CreditsFrameReader.ReadException("The film file cannot be found"));
        CreditsResult result = service.detect(due, file, guard::check);
        failedAt.remove(due.mediaFileId());
        SaveOutcome saved = service.save(due, result, clock.instant());
        if (saved == SaveOutcome.BUSY) {
            throw new Wait("a scan or an edit is using the library");
        }
        if (saved == SaveOutcome.SAVED) {
            LOG.info("Credits of {}: {}, {}, took {} s", due.title(), result.verdict(), offset(result.offsetMs()),
                Duration.between(started, clock.instant()).toSeconds());
        } else {
            LOG.info("Credits of {} not stored: the file changed while it was analysed", due.title());
        }
    }

    private static String offset(Long offsetMs) {
        if (offsetMs == null) {
            return "no offset";
        }
        long seconds = offsetMs / 1000;
        return "at %d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
