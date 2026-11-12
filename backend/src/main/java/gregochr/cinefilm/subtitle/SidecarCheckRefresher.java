package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearchFinished;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Checks the forced subtitle each edition serves against OpenSubtitles' file for the film
 * ({@link SubtitleComparison}), in the background and one run at a time: after the OpenSubtitles search ends, after a
 * scan, at start-up and daily. It is off while downloads are off (no username and password).
 *
 * <p>For each due edition ({@link SidecarCheckService#due}) it reuses the reference already downloaded for the film's
 * best file, or downloads it within the daily budget and stores it at once (a download is never repeated, and one that
 * cannot be read is stored as unusable and never fetched again). The result is saved only while the edition still
 * serves the version that was compared. A spent budget or an OpenSubtitles limit ends the run until the budget comes
 * back; an outage ends it with nothing stored (never a verdict); a failure on one edition is counted and the run
 * goes on. A request that arrives while a run is going makes it go round once more. Nothing here throws to its caller.
 */
@Service
public class SidecarCheckRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarCheckRefresher.class);

    /** A reset time further ahead than this is not believed (the reply is untrusted). */
    static final Duration MAX_BLOCK = Duration.ofHours(36);
    /** Why an edition is not checked when OpenSubtitles only has subtitles split over several files. */
    static final String MULTI_PART = "Only multi-part subtitles on OpenSubtitles";
    /** The largest reference kept, in characters of normalised SRT. */
    static final int MAX_REFERENCE_CHARS = 1024 * 1024;

    private final OpenSubtitlesApi api;
    private final OpenSubtitlesBudget budget;
    private final OpenSubtitlesReferenceRepository references;
    private final SidecarCheckService service;
    private final Clock clock;
    private final Executor executor;
    private final int dailyLimit;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();

    /** Why a run ends early: the budget is spent or OpenSubtitles' limit is reached. Not an error. */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop(String message) {
            super(message, null, false, false);
        }
    }

    /** Creates the service; the executor is {@code sidecarCheckExecutor}. */
    public SidecarCheckRefresher(OpenSubtitlesApi api, OpenSubtitlesBudget budget,
                                 OpenSubtitlesReferenceRepository references, SidecarCheckService service, Clock clock,
                                 @Qualifier("sidecarCheckExecutor") Executor executor,
                                 @Value("${cinefilm.opensubtitles.daily-download-limit:90}") int dailyLimit) {
        this.api = api;
        this.budget = budget;
        this.references = references;
        this.service = service;
        this.clock = clock;
        this.executor = executor;
        this.dailyLimit = dailyLimit;
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

    /** Checks after a search run ends (films may have a new best result). */
    @EventListener
    public void onSearchFinished(OpenSubtitlesSearchFinished event) {
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
            for (DueCheck due : service.due()) {
                try {
                    if (due.osFileCount() > 1) {
                        // Never compare part of a film against the whole: no download, no verdict.
                        multiPart++;
                        LOG.info("Edition {} not checked: {}", due.editionId(), MULTI_PART);
                        continue;
                    }
                    OpenSubtitlesReference reference = reference(due);
                    if (!reference.isUsable()) {
                        unusable++;
                    } else if (service.save(due, reference,
                        SubtitleComparison.compare(due.cues(), SrtParser.cues(reference.getCuesText())),
                        clock.instant())) {
                        saved++;
                    } else {
                        dropped++;
                    }
                } catch (Stop e) {
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
        } catch (RuntimeException e) {
            LOG.error("Subtitle file checks failed", e);
        }
    }

    /**
     * The reference for the film's best OpenSubtitles file: the stored one, or a download within the budget, stored
     * before anything else is done with it.
     */
    private OpenSubtitlesReference reference(DueCheck due) {
        // Only a reference fetched for the film's current IMDb id may be reused.
        OpenSubtitlesReference stored = references
            .findByFilmIdAndImdbIdAndOsFileId(due.filmId(), due.imdbId(), due.osFileId()).orElse(null);
        if (stored != null && stored.isFinal()) {
            return stored;
        }
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!budget.take(day, dailyLimit)) {
            throw new Stop("today's download budget is spent");
        }
        OpenSubtitlesDownload download;
        try {
            download = api.download(due.osFileId());
        } catch (OpenSubtitlesQuotaException e) {
            budget.exhaust(day, dailyLimit, cap(e.resetTimeUtc(), now));
            throw new Stop("OpenSubtitles' download limit is reached");
        } catch (OpenSubtitlesChargedException e) {
            // OpenSubtitles issued its link, so the account is charged: no refund, whatever went wrong after.
            OpenSubtitlesReference failed = recordFailure(due, stored, e.permanent(), now);
            if (e.permanent()) {
                return failed;
            }
            throw e;
        } catch (OpenSubtitlesUnavailableException e) {
            // Nothing was charged (the failure came before or during /download): give the download back.
            budget.refund(day);
            throw e;
        }
        OpenSubtitlesReference saved = store(due, stored, download, now);
        if (download.remaining() != null && download.remaining() <= 0) {
            budget.exhaust(day, dailyLimit, cap(download.resetTimeUtc(), now));
        }
        return saved;
    }

    /**
     * Records a charged download that gave no file: a permanent failure makes the file id final at once, a transient
     * one counts towards {@link OpenSubtitlesReference#MAX_FAILED_DOWNLOADS}.
     */
    private OpenSubtitlesReference recordFailure(DueCheck due, OpenSubtitlesReference stored, boolean permanent,
                                                 Instant now) {
        OpenSubtitlesReference reference = blank(due, stored, now);
        reference.setCuesText("");
        reference.setCueCount(0);
        reference.setFailedDownloads(permanent ? OpenSubtitlesReference.MAX_FAILED_DOWNLOADS
            : Math.min(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS, reference.getFailedDownloads() + 1));
        return save(due, reference);
    }

    /** The stored row to update, or a new one for the film's best file. */
    private static OpenSubtitlesReference blank(DueCheck due, OpenSubtitlesReference stored, Instant now) {
        OpenSubtitlesReference reference = stored != null ? stored : new OpenSubtitlesReference();
        reference.setFilmId(due.filmId());
        reference.setImdbId(due.imdbId());
        reference.setOsFileId(due.osFileId());
        reference.setRelease(due.osRelease());
        reference.setFetchedAt(now);
        return reference;
    }

    /** Stores a download as the film's reference: an unreadable or oversized one is kept as unusable and final. */
    private OpenSubtitlesReference store(DueCheck due, OpenSubtitlesReference stored, OpenSubtitlesDownload download,
                                         Instant now) {
        List<SubtitleCue> cues = download.parsed().cues();
        String text = SrtParser.format(cues);
        boolean usable = !cues.isEmpty() && text.length() <= MAX_REFERENCE_CHARS;
        OpenSubtitlesReference reference = blank(due, stored, now);
        reference.setCuesText(usable ? text : "");
        reference.setCueCount(usable ? cues.size() : 0);
        if (!usable) {
            reference.setFailedDownloads(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS);
        }
        return save(due, reference);
    }

    private OpenSubtitlesReference save(DueCheck due, OpenSubtitlesReference reference) {
        try {
            return references.saveAndFlush(reference);
        } catch (DataIntegrityViolationException e) {
            // Another writer stored it first: use theirs.
            return references.findByFilmIdAndImdbIdAndOsFileId(due.filmId(), due.imdbId(), due.osFileId())
                .orElseThrow(() -> e);
        }
    }

    /** The reset time, but never further ahead than {@link #MAX_BLOCK}; null stays null. */
    private static Instant cap(Instant reset, Instant now) {
        if (reset == null) {
            return null;
        }
        Instant latest = now.plus(MAX_BLOCK);
        return reset.isAfter(latest) ? latest : reset;
    }
}
