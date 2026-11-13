package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesDownload;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Gets one OpenSubtitles file as a stored reference, spending the shared daily download budget: the stored one when it
 * has one (a download is never repeated for a film, an IMDb id and a file id), otherwise a download stored at once. A
 * download that cannot be read is stored as unusable and never fetched again. Used by the check of the owner's files
 * and by the download of missing and replacement subtitles, which share the budget (each with its own ceiling).
 *
 * <p>Budget rules, as for the checks (decision 10a/10): a download is given back to the budget only when nothing was
 * charged (an outage before or during {@code /download}); once OpenSubtitles has issued its link the account is
 * charged and nothing is refunded, whatever goes wrong after. A quota answer, or an allowance of 0 left, spends the
 * day until the time OpenSubtitles gave (at most {@link #MAX_BLOCK} ahead).
 */
final class ReferenceFetcher {

    /** A reset time further ahead than this is not believed (the reply is untrusted). */
    static final Duration MAX_BLOCK = Duration.ofHours(36);
    /** The largest reference kept, in characters of normalised SRT. */
    static final int MAX_REFERENCE_CHARS = 1024 * 1024;

    private static final Object FETCH = new Object();

    private final OpenSubtitlesApi api;
    private final OpenSubtitlesBudget budget;
    private final OpenSubtitlesReferenceRepository references;
    private final Clock clock;
    private final DailyDownloadLimit limits;

    /** Why a run ends early: the budget is spent or OpenSubtitles' limit is reached. Not an error. */
    static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop(String message) {
            super(message, null, false, false);
        }
    }

    /** Creates the fetcher over the given collaborators. */
    ReferenceFetcher(OpenSubtitlesApi api, OpenSubtitlesBudget budget, OpenSubtitlesReferenceRepository references,
                     Clock clock, DailyDownloadLimit limits) {
        this.api = api;
        this.budget = budget;
        this.references = references;
        this.clock = clock;
        this.limits = limits;
    }

    /**
     * The reference for one OpenSubtitles file: the stored one, or a download within the budget, stored before
     * anything else is done with it.
     *
     * @param filmId the film
     * @param imdbId the IMDb id the file was found for (only a reference fetched for it is reused)
     * @param candidate the search result
     * @param limit the most downloads this caller may bring today's total to
     * @return the reference (check {@link OpenSubtitlesReference#isUsable})
     * @throws Stop when the budget up to {@code limit} is spent or OpenSubtitles' limit is reached
     * @throws OpenSubtitlesChargedException when the account was charged but the file could not be fetched, and it may
     *     be tried again
     * @throws OpenSubtitlesUnavailableException when OpenSubtitles cannot answer (nothing was charged)
     */
    OpenSubtitlesReference fetch(Long filmId, String imdbId, OpenSubtitlesResult candidate, int limit) {
        // One fetch at a time across the check and the download runs (OpenSubtitles paces them anyway): the second
        // run finds the stored reference instead of spending the quota again, and there is no per-file lock to leak.
        synchronized (FETCH) {
            return fetchLocked(filmId, imdbId, candidate, limit);
        }
    }

    private OpenSubtitlesReference fetchLocked(Long filmId, String imdbId, OpenSubtitlesResult candidate, int limit) {
        // Only a reference fetched for the film's current IMDb id may be reused.
        OpenSubtitlesReference stored = references
            .findByFilmIdAndImdbIdAndOsFileId(filmId, imdbId, candidate.fileId()).orElse(null);
        if (stored != null && stored.isFinal()) {
            return stored;
        }
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!budget.take(day, limit)) {
            throw new Stop("today's download budget is spent");
        }
        OpenSubtitlesDownload download;
        try {
            download = api.download(candidate.fileId());
        } catch (OpenSubtitlesQuotaException e) {
            budget.exhaust(day, limit, cap(e.resetTimeUtc(), now));
            throw new Stop("OpenSubtitles' download limit is reached");
        } catch (OpenSubtitlesChargedException e) {
            // OpenSubtitles issued its link, so the account is charged: no refund, whatever went wrong after.
            OpenSubtitlesReference failed = recordFailure(filmId, imdbId, candidate, stored, e.permanent(), now);
            if (e.permanent()) {
                return failed;
            }
            throw e;
        } catch (OpenSubtitlesUnavailableException e) {
            // Nothing was charged (the failure came before or during /download): give the download back.
            budget.refund(day);
            throw e;
        }
        OpenSubtitlesReference saved = store(filmId, imdbId, candidate, stored, download, now);
        syncWithAccount(day, download.remaining());
        if (download.remaining() != null && download.remaining() <= 0) {
            budget.exhaust(day, limit, cap(download.resetTimeUtc(), now));
        }
        return saved;
    }

    /**
     * OpenSubtitles says how many downloads are left: when that is fewer than the day's limit less our own count, the
     * account is shared with something else, so our count is raised to match (it is never lowered). Runs under the
     * fetch lock, so the check run and the download run cannot interleave here.
     */
    private void syncWithAccount(LocalDate day, Integer remaining) {
        if (remaining == null) {
            return;
        }
        int used = limits.today() - Math.max(0, remaining);
        if (used > 0) {
            budget.raiseUsed(day, used);
        }
    }

    /**
     * Records a charged download that gave no file: a permanent failure makes the file id final at once, a transient
     * one counts towards {@link OpenSubtitlesReference#MAX_FAILED_DOWNLOADS}.
     */
    private OpenSubtitlesReference recordFailure(Long filmId, String imdbId, OpenSubtitlesResult candidate,
                                                 OpenSubtitlesReference stored, boolean permanent, Instant now) {
        OpenSubtitlesReference reference = blank(filmId, imdbId, candidate, stored, now);
        reference.setCuesText("");
        reference.setCueCount(0);
        reference.setFailedDownloads(permanent ? OpenSubtitlesReference.MAX_FAILED_DOWNLOADS
            : Math.min(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS, reference.getFailedDownloads() + 1));
        return save(filmId, imdbId, candidate, reference);
    }

    /** The stored row to update, or a new one for the candidate's file. */
    private static OpenSubtitlesReference blank(Long filmId, String imdbId, OpenSubtitlesResult candidate,
                                                OpenSubtitlesReference stored, Instant now) {
        OpenSubtitlesReference reference = stored != null ? stored : new OpenSubtitlesReference();
        reference.setFilmId(filmId);
        reference.setImdbId(imdbId);
        reference.setOsFileId(candidate.fileId());
        reference.setRelease(candidate.release());
        reference.setFetchedAt(now);
        return reference;
    }

    /** Stores a download as the film's reference: an unreadable or oversized one is kept as unusable and final. */
    private OpenSubtitlesReference store(Long filmId, String imdbId, OpenSubtitlesResult candidate,
                                         OpenSubtitlesReference stored, OpenSubtitlesDownload download, Instant now) {
        List<SubtitleCue> cues = download.parsed().cues();
        String text = SrtParser.format(cues);
        boolean usable = !cues.isEmpty() && text.length() <= MAX_REFERENCE_CHARS;
        OpenSubtitlesReference reference = blank(filmId, imdbId, candidate, stored, now);
        reference.setCuesText(usable ? text : "");
        reference.setCueCount(usable ? cues.size() : 0);
        if (!usable) {
            reference.setFailedDownloads(OpenSubtitlesReference.MAX_FAILED_DOWNLOADS);
        }
        return save(filmId, imdbId, candidate, reference);
    }

    private OpenSubtitlesReference save(Long filmId, String imdbId, OpenSubtitlesResult candidate,
                                        OpenSubtitlesReference reference) {
        try {
            return references.saveAndFlush(reference);
        } catch (DataIntegrityViolationException e) {
            // Another writer stored it first: use theirs.
            return references.findByFilmIdAndImdbIdAndOsFileId(filmId, imdbId, candidate.fileId())
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
