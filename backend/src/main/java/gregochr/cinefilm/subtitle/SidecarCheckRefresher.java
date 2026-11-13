package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearchFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.ReferenceWalk.Attempt;
import gregochr.cinefilm.subtitle.SubtitleComparison.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Checks the forced subtitle each edition serves against OpenSubtitles' files for the film
 * ({@link SubtitleComparison}), in the background and one run at a time: after the OpenSubtitles search ends, after a
 * scan, at start-up and daily. It is off while downloads are off (no username and password).
 *
 * <p>For each due edition ({@link SidecarCheckService#due}) it walks the film's candidate files, the best fit for the
 * edition's cut first ({@link ReferenceWalk}): each is reused when already downloaded, or downloaded within the daily
 * budget and stored at once (a download is never repeated, and one that cannot be read is stored as unusable and never
 * fetched again). It stops at the first file that agrees, or moves to the next when a file was full subtitles or looks
 * like another cut, comparing with at most three files per edition and IMDb id, ever. The best result found is saved
 * only while the edition still serves the version that was compared. A spent budget or an OpenSubtitles limit ends the
 * run until the budget comes back; an outage ends it with nothing stored (never a verdict); a failure on one edition
 * is counted and the run goes on. A request that arrives while a run is going makes it go round once more. Nothing
 * here throws to its caller.
 */
@Service
public class SidecarCheckRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarCheckRefresher.class);

    /** A reset time further ahead than this is not believed (the reply is untrusted). */
    static final Duration MAX_BLOCK = ReferenceFetcher.MAX_BLOCK;
    /** Why an edition is not checked when OpenSubtitles only has subtitles split over several files. */
    static final String MULTI_PART = "Only multi-part subtitles on OpenSubtitles";
    /** The largest reference kept, in characters of normalised SRT. */
    static final int MAX_REFERENCE_CHARS = ReferenceFetcher.MAX_REFERENCE_CHARS;

    private final OpenSubtitlesApi api;
    private final OpenSubtitlesBudget budget;
    private final ReferenceFetcher fetcher;
    private final SidecarCheckService service;
    private final Clock clock;
    private final Executor executor;
    private final DailyDownloadLimit limits;
    /** The ceiling for comparisons, set at the start of each run from the day's limit; one run at a time. */
    private volatile int checkLimit;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile Instant lastFinishedAt;

    /** Creates the service; the executor is {@code sidecarCheckExecutor}. */
    public SidecarCheckRefresher(OpenSubtitlesApi api, OpenSubtitlesBudget budget,
                                 OpenSubtitlesReferenceRepository references, SidecarCheckService service, Clock clock,
                                 @Qualifier("sidecarCheckExecutor") Executor executor,
                                 DailyDownloadLimit limits,
                                 ApplicationEventPublisher events) {
        this.api = api;
        this.budget = budget;
        this.fetcher = new ReferenceFetcher(api, budget, references, clock, limits);
        this.service = service;
        this.clock = clock;
        this.executor = executor;
        // The comparisons may bring the day's total only to their share, so downloads of missing and replacement
        // subtitles are never starved (DownloadShares).
        this.limits = limits;
        this.events = events;
    }

    /** Checks at start-up, and says so when the check is off. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!startDue() && !api.downloadsEnabled()) {
            LOG.info("Subtitle file checks are off: OpenSubtitles downloads need OPENSUBTITLES_API_KEY, "
                + "OPENSUBTITLES_USERNAME and OPENSUBTITLES_PASSWORD");
        }
    }

    /** Checks after a library scan (a file may have been added, moved or changed). */
    @EventListener
    public void onScanFinished(ScanFinished event) {
        startDue();
    }

    /** Checks after a search run ends (films may have new candidates). */
    @EventListener
    public void onSearchFinished(OpenSubtitlesSearchFinished event) {
        startDue();
    }

    /**
     * Checks after a download was added: with the timing check off nothing else would ask for its second opinion (with
     * it on, the download waits for its fit and the timing pass's own event asks).
     */
    @EventListener
    public void onDownloadsFinished(SubtitleDownloadsFinished event) {
        startDue();
    }

    /** Checks after a timing pass fitted something (a download its timing could not prove is compared instead). */
    @EventListener
    public void onTimingFinished(SidecarTimingFinished event) {
        startDue();
    }

    /**
     * Asks for a check run in the background, unless downloads are off. When one is already running the request is not
     * lost: the running one goes round again before it finishes.
     *
     * @return true when this call started a run; false when downloads are off or a run was already going
     */
    public boolean startDue() {
        if (!api.downloadsEnabled()) {
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
                try {
                    run();
                } finally {
                    // Stamped when a pass ends, however it ends: Admin reads the files again when this changes.
                    lastFinishedAt = clock.instant();
                }
            }
        } finally {
            running.set(false);
        }
        // A request that arrived after the last check but before the run was given up would otherwise be lost.
        if (pending.get()) {
            launch();
        }
    }

    /** True while a check run is going (Admin polls for it). */
    public boolean isRunning() {
        return running.get();
    }

    /** When the last pass over the due editions ended, success or not; null until one has since start-up. */
    public Instant lastFinishedAt() {
        return lastFinishedAt;
    }

    /** One pass over the due editions. */
    void run() {
        int saved = 0;
        int dropped = 0;
        int unusable = 0;
        int multiPart = 0;
        int failed = 0;
        try {
            Instant blockedUntil = budget.blockedUntil(clock.instant());
            if (blockedUntil != null) {
                LOG.info("Subtitle file checks wait: OpenSubtitles' download limit is reached until {}", blockedUntil);
                return;
            }
            // The comparisons may bring the day's total only to their share; the limit follows the account.
            checkLimit = DownloadShares.forChecks(limits.today());
            for (DueCheck due : service.due()) {
                try {
                    if (due.candidates().isEmpty()) {
                        // Never compare part of a film against the whole: no download, no verdict.
                        multiPart++;
                        LOG.info("Edition {} not checked: {}", due.editionId(), MULTI_PART);
                        continue;
                    }
                    Attempt kept = walk(due);
                    if (kept == null) {
                        unusable++;
                    } else if (service.save(due, kept.reference(), kept.result(), clock.instant())) {
                        saved++;
                    } else {
                        dropped++;
                    }
                } catch (ReferenceFetcher.Stop e) {
                    LOG.info("Subtitle file checks stopped: {}", e.getMessage());
                    break;
                } catch (OpenSubtitlesUnavailableException e) {
                    LOG.warn("Subtitle file checks stopped: {}", e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("Could not check the subtitle of edition {}: {}", due.editionId(),
                        e.getClass().getSimpleName());
                }
            }
            LOG.info("Subtitle file checks finished: {} saved, {} changed meanwhile, {} unusable references, "
                + "{} failed", saved, dropped, unusable, failed);
            if (saved > 0) {
                announce();
            }
        } catch (RuntimeException e) {
            LOG.error("Subtitle file checks failed", e);
        }
    }

    /** Says a check was saved, so a file that failed can be replaced; a listener's failure does not touch the run. */
    private void announce() {
        try {
            events.publishEvent(new SidecarChecksFinished());
        } catch (RuntimeException e) {
            LOG.warn("A listener of the subtitle file checks failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Compares the edition's served cues with its OpenSubtitles candidates, the best fit first, and says which result
     * to keep ({@link ReferenceWalk}). A candidate whose reference is stored is compared locally, with no download; a
     * new one is downloaded within the budget. At most {@value ReferenceWalk#MAX_CANDIDATES} files are ever compared
     * per edition and IMDb id (the ledger is persisted). An outage, a spent budget or a charged transient failure on
     * the way ends the walk with nothing saved; the references already stored are used again by the next walk.
     *
     * @return the reference and result to save, or null when no candidate had a usable reference
     */
    private Attempt walk(DueCheck due) {
        Set<Long> tried = new HashSet<>(due.triedFileIds());
        List<Attempt> attempts = new ArrayList<>();
        int position = 0;
        for (OpenSubtitlesResult candidate : due.candidates()) {
            position++;
            boolean known = tried.contains(candidate.fileId());
            if (!known && tried.size() >= ReferenceWalk.MAX_CANDIDATES) {
                LOG.info("Edition {}: candidate {} (file {}) skipped, {} OpenSubtitles files were already tried",
                    due.editionId(), position, candidate.fileId(), tried.size());
                // Files already tried further down are still reused: that costs no download and keeps the cap.
                continue;
            }
            OpenSubtitlesReference reference = tried(due, candidate, known);
            tried.add(candidate.fileId());
            if (!reference.isUsable()) {
                LOG.info("Edition {}: candidate {} (file {}) skipped, it could not be read", due.editionId(),
                    position, candidate.fileId());
                continue;
            }
            Result result = SubtitleComparison.compare(due.cues(), SrtParser.cues(reference.getCuesText()));
            attempts.add(new Attempt(reference, result));
            if (!ReferenceWalk.moveOn(result)) {
                break;
            }
            LOG.info("Edition {}: candidate {} (file {}) was {}, trying the next", due.editionId(), position,
                candidate.fileId(), result.fullReference() ? "full subtitles" : "probably another cut");
        }
        if (attempts.isEmpty()) {
            return null;
        }
        Attempt kept = ReferenceWalk.choose(attempts);
        LOG.info("Edition {}: used OpenSubtitles file {} (compared {}), {}", due.editionId(),
            kept.reference().getOsFileId(), attempts.size(), kept.result().verdict());
        return new Attempt(kept.reference(), kept.result().withReasons(ReferenceWalk.reasons(attempts, kept)));
    }

    /** The candidate's reference, put on the ledger of the edition's candidates (a charged failure counts too). */
    private OpenSubtitlesReference tried(DueCheck due, OpenSubtitlesResult candidate, boolean known) {
        OpenSubtitlesReference reference;
        try {
            reference = reference(due, candidate);
        } catch (OpenSubtitlesChargedException e) {
            if (!known) {
                service.markTried(due.editionId(), due.imdbId(), candidate.fileId(), clock.instant());
            }
            throw e;
        }
        if (!known) {
            service.markTried(due.editionId(), due.imdbId(), candidate.fileId(), clock.instant());
        }
        return reference;
    }

    /** The reference for one candidate, within the share of the budget the checks may use. */
    private OpenSubtitlesReference reference(DueCheck due, OpenSubtitlesResult candidate) {
        return fetcher.fetch(due.filmId(), due.imdbId(), candidate, checkLimit);
    }
}
