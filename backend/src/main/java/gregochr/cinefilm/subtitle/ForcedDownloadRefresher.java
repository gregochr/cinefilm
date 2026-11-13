package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearchFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedDownloadService.AddOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Downloads forced subtitles from OpenSubtitles for the editions that need one ({@link ForcedDownloadService#due}), in
 * the background and one run at a time: at start-up, after a scan, after the OpenSubtitles search and the checks of the
 * owner's files end (a failed file is what makes a replacement due), after the owner removes a download, and daily. It
 * is off while downloads are off (no username and password).
 *
 * <p>Per edition, a stored reference that fits is promoted first (it costs no download); otherwise the best candidate
 * for the edition's cut is fetched through {@link ReferenceFetcher} (stored at once, never fetched twice) and made the
 * edition's download unless it is full subtitles or unreadable, then the next. The day's budget is shared with the
 * comparisons of {@link DownloadShares}: promotions first (free), then replacements up to their ceiling (the owner's
 * file failed, so the film shows nothing now), then missing subtitles up to the whole limit. The day's limit is read
 * from {@link DailyDownloadLimit} at the start of each run. A spent budget or an OpenSubtitles limit ends the run; an
 * outage ends it with nothing added; a failure on one edition is counted and the run goes on. A download that was
 * added is then proven by its timing fit, or a
 * second opinion from another OpenSubtitles file ({@link SubtitleDownloadsFinished} starts the timing check). A request
 * that arrives while a run is going makes it go round once more. Nothing here throws to its caller.
 */
@Service
public class ForcedDownloadRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(ForcedDownloadRefresher.class);

    private final OpenSubtitlesApi api;
    private final OpenSubtitlesBudget budget;
    private final ReferenceFetcher fetcher;
    private final ForcedDownloadService service;
    private final Clock clock;
    private final Executor executor;
    private final DailyDownloadLimit limits;
    /** The day's limit, read at the start of each run; one run at a time. */
    private volatile int dailyLimit;
    private final ApplicationEventPublisher events;
    private final Object guard = new Object();
    private boolean running;
    private boolean pending;
    private volatile Instant lastFinishedAt;
    private final AtomicBoolean deferred = new AtomicBoolean();

    /** Creates the service; the executor is {@code forcedDownloadExecutor}. */
    public ForcedDownloadRefresher(OpenSubtitlesApi api, OpenSubtitlesBudget budget,
                                   OpenSubtitlesReferenceRepository references, ForcedDownloadService service,
                                   Clock clock, @Qualifier("forcedDownloadExecutor") Executor executor,
                                   DailyDownloadLimit limits,
                                   ApplicationEventPublisher events) {
        this.api = api;
        this.budget = budget;
        this.fetcher = new ReferenceFetcher(api, budget, references, clock, limits);
        this.service = service;
        this.clock = clock;
        this.executor = executor;
        this.limits = limits;
        this.events = events;
    }

    /** Downloads at start-up, and says so when downloads are off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!startDue() && !api.downloadsEnabled()) {
            LOG.info("Subtitle downloads are off: OpenSubtitles downloads need OPENSUBTITLES_API_KEY, "
                + "OPENSUBTITLES_USERNAME and OPENSUBTITLES_PASSWORD");
        }
    }

    /** Downloads after a library scan (a film or a subtitle file may have been added, moved or changed). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /** Downloads after a search run ends (a film may now have candidates, and need). */
    @EventListener
    public void onSearchFinished(OpenSubtitlesSearchFinished event) {
        startDue();
    }

    /** Downloads after a check run saved something (an owner's file may have failed). */
    @EventListener
    public void onChecksFinished(SidecarChecksFinished event) {
        startDue();
    }

    /** Looks for the next candidate after the owner removed a download. */
    @EventListener
    public void onDownloadRemoved(SubtitleDownloadRemoved event) {
        startDue();
    }

    /**
     * Asks for a download run in the background, unless downloads are off. When one is already running the request is
     * not lost: the running one goes round again before it finishes. The two flags change together under one lock, so
     * a request can never fall between the last look for work and the run giving itself up.
     *
     * @return true when this call started a run; false when downloads are off or a run was already going
     */
    public boolean startDue() {
        if (!api.downloadsEnabled()) {
            return false;
        }
        synchronized (guard) {
            pending = true;
            if (running) {
                return false;
            }
            running = true;
        }
        try {
            executor.execute(this::worker);
        } catch (RuntimeException e) {
            synchronized (guard) {
                running = false;
            }
            throw e;
        }
        return true;
    }

    private void worker() {
        while (takePending()) {
            try {
                run();
            } finally {
                lastFinishedAt = clock.instant();
            }
        }
    }

    /** Takes the pending request, or, when there is none, gives the run up in the same step. */
    private boolean takePending() {
        synchronized (guard) {
            if (!pending) {
                running = false;
                return false;
            }
            pending = false;
            return true;
        }
    }

    /**
     * Asks for a run only when the last one could not add a download because a scan or an Admin edit held the library
     * lock (the file is stored, so the retry costs nothing): the hourly nudge, so it is not left for the next day.
     */
    public void startIfDeferred() {
        if (deferred.get()) {
            startDue();
        }
    }

    /** True while a download run is going. */
    public boolean isRunning() {
        synchronized (guard) {
            return running;
        }
    }

    /** When the last pass ended, success or not; null until one has since start-up. */
    public Instant lastFinishedAt() {
        return lastFinishedAt;
    }

    /** One pass over the due editions. */
    void run() {
        deferred.set(false);
        int added = 0;
        int nothing = 0;
        int failed = 0;
        try {
            Instant blockedUntil = budget.blockedUntil(clock.instant());
            if (blockedUntil != null) {
                LOG.info("Subtitle downloads wait: OpenSubtitles' download limit is reached until {}", blockedUntil);
                return;
            }
            dailyLimit = limits.today();
            boolean replacementsDone = false;
            for (DueDownload due : service.due()) {
                if (replacementsDone && due.kind() == Kind.REPLACEMENT && due.promotion() == null) {
                    continue;
                }
                try {
                    if (fill(due)) {
                        added++;
                    } else {
                        nothing++;
                    }
                } catch (ReferenceFetcher.Stop e) {
                    if (e.ceiling() && due.kind() == Kind.REPLACEMENT) {
                        LOG.info("Subtitle downloads: replacements reached their share of today's budget, "
                            + "going on with missing subtitles");
                        replacementsDone = true;
                        continue;
                    }
                    LOG.info("Subtitle downloads stopped: {}", e.getMessage());
                    break;
                } catch (OpenSubtitlesUnavailableException e) {
                    LOG.warn("Subtitle downloads stopped: {}", e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("Could not download a subtitle for edition {}: {}", due.editionId(),
                        e.getClass().getSimpleName());
                }
            }
            LOG.info("Subtitle downloads finished: {} added, {} with nothing added, {} failed", added, nothing,
                failed);
            if (added > 0) {
                announce();
            }
        } catch (RuntimeException e) {
            LOG.error("Subtitle downloads failed", e);
        }
    }

    /** Says a download was added, so its timing is fitted; a listener's failure does not touch the run. */
    private void announce() {
        try {
            events.publishEvent(new SubtitleDownloadsFinished());
        } catch (RuntimeException e) {
            LOG.warn("A listener of the subtitle downloads failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Gives an edition its download: the promotion when it has one, else the best candidate that turns out usable.
     *
     * @return true when a download was added
     */
    private boolean fill(DueDownload due) {
        if (due.promotion() != null) {
            AddOutcome outcome = service.add(due, due.promotion(), clock.instant());
            LOG.info("Edition {}: promoted OpenSubtitles file {} already downloaded, {}", due.editionId(),
                due.promotion().getOsFileId(), outcome);
            if (outcome == AddOutcome.BUSY) {
                deferred.set(true);
            }
            return outcome == AddOutcome.ADDED;
        }
        int limit = due.kind() == Kind.MISSING ? DownloadShares.forMissing(dailyLimit)
            : DownloadShares.forReplacements(dailyLimit);
        if (due.remainingFetches() <= 0) {
            LOG.info("Edition {}: no more OpenSubtitles files are tried (at most {} are ever made into downloads)",
                due.editionId(), DownloadPlan.MAX_DOWNLOADS);
            return false;
        }
        // The cap counts files made into downloads; a candidate that is full or unreadable is stored (never fetched
        // again) and the next one is tried at once, bounded by the candidate list and the day's quota.
        for (OpenSubtitlesResult candidate : due.candidates()) {
            OpenSubtitlesReference reference = fetcher.fetch(due.filmId(), due.imdbId(), candidate, limit);
            if (!DownloadPlan.promotable(reference)) {
                LOG.info("Edition {}: OpenSubtitles file {} skipped, {}", due.editionId(), candidate.fileId(),
                    reference.isUsable() ? "it is full subtitles" : "it could not be read");
                continue;
            }
            AddOutcome outcome = service.add(due, reference, clock.instant());
            LOG.info("Edition {}: downloaded OpenSubtitles file {} for a {} subtitle, {}", due.editionId(),
                candidate.fileId(), due.kind().name().toLowerCase(Locale.ROOT), outcome);
            if (outcome == AddOutcome.BUSY) {
                deferred.set(true);
            }
            return outcome == AddOutcome.ADDED;
        }
        return false;
    }
}
