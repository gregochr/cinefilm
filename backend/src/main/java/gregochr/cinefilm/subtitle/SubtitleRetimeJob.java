package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import gregochr.cinefilm.transcode.KeyframeIndexPassFinished;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * Puts downloaded subtitles on the film's timeline (docs/engineering/subtitle-frame-rate-plan.md, "The job"), at
 * start-up, nightly ({@link SubtitleRetimeScheduler}) and after each pass of the keyframe index (a film's rate may
 * have arrived), one download at a time and one run at a time, in the background. {@code cinefilm.subtitles.retime} is
 * {@code off} (the default: nothing is read or written), {@code on}, or {@code restore} (the rollback: every retimed
 * download is put back). Each download is visited by {@link SubtitleRetimeService#process}, which takes the library
 * lock for that one row; a row whose lock was busy is visited again on the next run. A run that rewrote or restored
 * something publishes {@link SubtitleCuesRewritten}. A request that arrives while a run is going makes it go round
 * once more. Nothing here throws to its caller.
 */
@Service
public class SubtitleRetimeJob {

    private static final Logger LOG = LoggerFactory.getLogger(SubtitleRetimeJob.class);

    private final SubtitleRetimeService service;
    private final Executor executor;
    private final RetimeMode mode;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();

    /** Creates the job; the executor is {@code timingCheckExecutor}, so a run never overlaps a timing check. */
    public SubtitleRetimeJob(SubtitleRetimeService service,
                             @Qualifier("timingCheckExecutor") Executor executor,
                             @Value("${cinefilm.subtitles.retime:off}") String setting,
                             ApplicationEventPublisher events) {
        this.service = service;
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
        Set<Long> changed = new HashSet<>();
        int failed = 0;
        try {
            for (Long id : service.candidateIds()) {
                try {
                    Outcome outcome = service.process(id, mode);
                    counts.merge(outcome, 1, Integer::sum);
                    if (outcome == Outcome.REWRITTEN || outcome == Outcome.RESTORED) {
                        changed.add(id);
                    }
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("Could not retime download {}: {}", id, e.getClass().getSimpleName());
                }
            }
            if (!changed.isEmpty()) {
                events.publishEvent(new SubtitleCuesRewritten(Set.copyOf(changed)));
            }
        } catch (RuntimeException e) {
            LOG.error("Subtitle retime failed", e);
        }
        LOG.info("Subtitle retime ({}) finished: {} rewritten, {} restored, {} set off, {} fit as they are, "
                + "{} left as they were, {} left for the next run (library busy), {} failed", mode,
            count(counts, Outcome.REWRITTEN), count(counts, Outcome.RESTORED), count(counts, Outcome.DECIDED_OFF),
            count(counts, Outcome.DECIDED_FITS), count(counts, Outcome.UNCHANGED), count(counts, Outcome.BUSY),
            failed);
    }

    private static int count(Map<Outcome, Integer> counts, Outcome outcome) {
        return counts.getOrDefault(outcome, 0);
    }
}
