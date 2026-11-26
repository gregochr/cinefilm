package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import gregochr.cinefilm.transcode.KeyframeIndexPassFinished;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Puts downloaded subtitles on the film's timeline (docs/engineering/subtitle-frame-rate-plan.md, "The job"), at
 * start-up, nightly ({@link SubtitleRetimeScheduler}) and after each pass of the keyframe index (a film's rate may
 * have arrived), one download at a time and one run at a time, in the background. {@code cinefilm.subtitles.retime} is
 * {@code on} (the default), {@code off} (nothing is read or written), or {@code restore} (the rollback: every retimed
 * download is put back). Each download is visited by {@link SubtitleRetimeService#process}, which takes the library
 * lock for that one row; a row whose lock stays busy through {@link BusyRetry} is visited again on the next run. A run
 * that rewrote or restored something publishes {@link SubtitleCuesRewritten} and then fits and checks the touched
 * editions at once ({@link SubtitleRetimeRecheck}). A request that arrives while a run is going makes it go round once
 * more. The line "Subtitle retime (mode) finished" is logged last, after the re-check. Nothing here throws to its
 * caller.
 */
@Service
public class SubtitleRetimeJob {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleRetimeJob.class);

    private final SubtitleRetimeService service;
    private final ReferenceRetimeService referenceService;
    private final SubtitleRetimeRecheck recheck;
    private final BusyRetry retry;
    private final Executor executor;
    private final RetimeMode mode;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();

    /** Creates the job; the executor is {@code timingCheckExecutor}, so a run never overlaps a timing check. */
    public SubtitleRetimeJob(SubtitleRetimeService service, ReferenceRetimeService referenceService,
                             SubtitleRetimeRecheck recheck, BusyRetry retry,
                             @Qualifier("timingCheckExecutor") Executor executor,
                             @Value("${cinefilm.subtitles.retime:on}") String setting,
                             ApplicationEventPublisher events) {
        this.service = service;
        this.referenceService = referenceService;
        this.recheck = recheck;
        this.retry = retry;
        this.executor = executor;
        this.mode = RetimeMode.parse(setting);
        this.events = events;
    }

    /** Runs at start-up. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        start();
    }

    /** Runs after a pass of the keyframe index: a film's frame rate may have arrived. */
    @EventListener
    public void onIndexPassFinished(KeyframeIndexPassFinished event) {
        start();
    }

    /**
     * Runs after the timing check fitted something: a download that waited for its fit (the OFF_FITS decision) is
     * looked at again. A run that changes nothing publishes nothing, so this cannot loop.
     */
    @EventListener
    public void onTimingFinished(SidecarTimingFinished event) {
        start();
    }

    /**
     * Asks for a run in the background. When one is going it goes round once more before it finishes, so a request
     * (a rate that arrived during the run) is not lost.
     *
     * @return true when this call started a run; false when the setting is off or a run was already going
     */
    public boolean start() {
        if (mode == RetimeMode.OFF) {
            return false;
        }
        // Set before trying to take the run: a run that is just finishing re-checks it after giving the run up.
        pending.set(true);
        return launch();
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
            afterLastCheck();
        } finally {
            running.set(false);
        }
        // A request that arrived after the last pass but before the run was given up would otherwise be lost.
        if (pending.get()) {
            launch();
        }
    }

    /** Called after the last look at the request flag and before the run is given up: a seam for the test. */
    void afterLastCheck() {
    }

    /** One pass over every download that may need retiming. */
    void run() {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        Map<Outcome, Integer> referenceCounts = new EnumMap<>(Outcome.class);
        Set<Long> changed = new HashSet<>();
        Set<Long> changedReferences = new HashSet<>();
        int[] failed = {0};
        boolean downloadsSkipped = false;
        try {
            // Stored references first: the re-check then compares with references already on the film's timeline.
            downloadsSkipped = visitAll("reference", referenceService.candidateIds(), referenceService::process,
                referenceCounts, changedReferences, failed);
            if (!downloadsSkipped) {
                visitAll("download", service.candidateIds(), service::process, counts, changed, failed);
            }
            if (!changed.isEmpty() || !changedReferences.isEmpty()) {
                // Re-check, publish, then announce the fits; do not reorder. The event goes after the re-check: its
                // listener books a timing pass on this thread that would hold the refresher's run flag, so every fit
                // of the re-check would be left to it. The event also makes the probes forget. Announcing goes last:
                // it starts a check pass, which would otherwise measure a sidecar before the forget and again after.
                // A pass already running meanwhile may still measure one twice: accepted (the probes only log).
                Set<Long> ids = Set.copyOf(changed);
                boolean fitted = recheck.run(ids, Set.copyOf(changedReferences));
                events.publishEvent(new SubtitleCuesRewritten(ids));
                if (fitted) {
                    recheck.announceFits();
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Subtitle retime failed", e);
            failed[0]++;
        }
        LOG.info("Subtitle retime ({}) finished: {} rewritten, {} restored, {} set off, {} fit as they are, "
                + "{} left as they were, {} left for the next run (library busy), {} failed; references: {} rewritten, "
                + "{} restored, {} left for the next run (library busy){}", mode,
            count(counts, Outcome.REWRITTEN), count(counts, Outcome.RESTORED), count(counts, Outcome.DECIDED_OFF),
            count(counts, Outcome.DECIDED_FITS), count(counts, Outcome.UNCHANGED), count(counts, Outcome.BUSY),
            failed[0], count(referenceCounts, Outcome.REWRITTEN), count(referenceCounts, Outcome.RESTORED),
            count(referenceCounts, Outcome.BUSY),
            downloadsSkipped ? "; DOWNLOADS NOT VISITED: the run stopped at a busy library" : "");
    }

    /**
     * Visits each row in turn: a busy library is tried again a bounded number of times, then the rest is left for the
     * next run. One that throws is counted and passed over.
     *
     * @return true when the library stayed busy, so the run stops
     */
    private boolean visitAll(String what, List<Long> ids, BiFunction<Long, RetimeMode, Outcome> process,
                             Map<Outcome, Integer> counts, Set<Long> changed, int[] failed) {
        for (Long id : ids) {
            try {
                Outcome outcome = visit(what, id, process);
                counts.merge(outcome, 1, Integer::sum);
                if (outcome == Outcome.REWRITTEN || outcome == Outcome.RESTORED) {
                    changed.add(id);
                }
                if (outcome == Outcome.BUSY) {
                    // A scan or an edit holds the lock: the rest would wait as long, so the next run has them.
                    LOG.info("Subtitle retime stops: the library is busy");
                    return true;
                }
            } catch (RuntimeException e) {
                failed[0]++;
                LOG.warn("Could not retime {} {}: {}", what, id, e.getClass().getSimpleName());
            }
        }
        return false;
    }

    private Outcome visit(String what, Long id, BiFunction<Long, RetimeMode, Outcome> process) {
        Outcome[] outcome = new Outcome[1];
        retry.until("Retime of " + what + " " + id, () -> {
            outcome[0] = process.apply(id, mode);
            return outcome[0] != Outcome.BUSY;
        });
        return outcome[0];
    }

    private static int count(Map<Outcome, Integer> counts, Outcome outcome) {
        return counts.getOrDefault(outcome, 0);
    }
}
