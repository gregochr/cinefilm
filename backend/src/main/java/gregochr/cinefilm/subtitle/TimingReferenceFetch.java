package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import gregochr.cinefilm.subtitle.TimingReferenceService.File;
import gregochr.cinefilm.subtitle.TimingReferenceService.Target;
import gregochr.cinefilm.web.JsonEtag;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 3c-1 (forced-timing-reference-plan.md): finds a full English reference for the editions whose forced files have
 * no English track in the film to be timed against ({@link TimingReferenceRule}), and measures each forced file
 * against each usable one with {@link TimingFit}, writing one INFO line per file and reference. <strong>It stores no
 * fit and acts on nothing</strong>: the only rows written are the stored references ({@link ReferenceFetcher}); 3c-2
 * decides from the lines what to store and show.
 *
 * <p>Candidates are the film's single-file full results of a plausible frame rate, best for the edition's cut first
 * (a hearing-impaired file last). Each is taken from the stored references, or fetched until one is usable
 * ({@link TimingReferenceRule#usable}) and matches some forced file ({@link TimingReferenceRule#matches}: at least 30%
 * of its lines at an offset of 30 s or less). At most {@value TimingReferenceRule#MAX_FETCHES_PER_RUN} files are
 * fetched a run and {@value TimingReferenceRule#MAX_FETCHES_PER_DAY} a UTC day (counted in memory, so a restart may
 * allow two more), and at most {@value TimingReferenceRule#MAX_REFERENCES} are stored per film and IMDb id (counted
 * from the stored references, never from a ledger). One map of stored references per film and IMDb id is shared by
 * the run's editions, so two editions of one film never exceed the cap or fetch one file twice. Fetches go through the
 * check job's {@link ReferenceFetcher} with the comparison ceiling. A spent budget, an OpenSubtitles limit or an
 * outage ends the fetching of the pass quietly (measuring from stored references goes on: it costs nothing). Run by
 * {@link SidecarCheckRefresher} after the checks of the owner's files, so those are compared first.
 */
final class TimingReferenceFetch {

    private static final Logger LOG = LoggerFactory.getLogger(TimingReferenceFetch.class);
    private static final int PERCENT = 100;

    private final ReferenceFetcher fetcher;
    private final TimingReferenceService service;
    private final Clock clock;
    private final Set<String> done = ConcurrentHashMap.newKeySet();
    private LocalDate day;
    private int fetchedToday;

    /** A film's stored references for one IMDb id and how many count towards the cap, shared by a run's editions. */
    private static final class Shared {
        private final Map<Long, OpenSubtitlesReference> stored;
        private int kept;

        Shared(Target target) {
            this.stored = new HashMap<>(target.stored());
            this.kept = target.referencesKept();
        }
    }

    /** How a usable reference fared against the edition's forced files. */
    private record Judgement(boolean matches, double bestShare) {
    }

    /** Creates it over the check job's fetcher, so every fetch goes through its lock and ceilings. */
    TimingReferenceFetch(ReferenceFetcher fetcher, TimingReferenceService service, Clock clock) {
        this.fetcher = fetcher;
        this.service = service;
        this.clock = clock;
    }

    /** The fetches this run may make: the per-run limit, less what today (UTC) has used. */
    private synchronized int allowance() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            fetchedToday = 0;
        }
        return Math.max(0, Math.min(TimingReferenceRule.MAX_FETCHES_PER_RUN,
            TimingReferenceRule.MAX_FETCHES_PER_DAY - fetchedToday));
    }

    /** Books one fetch against the UTC day it happened in (a run may cross midnight). */
    private synchronized void counted() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            fetchedToday = 0;
        }
        fetchedToday++;
    }

    /**
     * One pass: fetches what is missing (unless {@code mayFetch} is false), then measures.
     *
     * @param checkLimit the most downloads the comparisons may bring today's total to
     * @param mayFetch false when the run has already met a spent budget, a limit or an outage
     * @return how many lines were written
     */
    int run(int checkLimit, boolean mayFetch) {
        int[] fetchesLeft = {mayFetch ? allowance() : 0};
        boolean stopped = !mayFetch;
        Map<List<Object>, Shared> films = new HashMap<>();
        int written = 0;
        for (Target target : service.targets()) {
            Shared shared = films.computeIfAbsent(List.of(target.filmId(), target.imdbId()),
                key -> new Shared(target));
            try {
                if (!stopped) {
                    fetchFor(target, shared, checkLimit, fetchesLeft);
                }
            } catch (ReferenceFetcher.Stop e) {
                LOG.info("Timing reference fetching stopped: {}", e.getMessage());
                stopped = true;
            } catch (OpenSubtitlesUnavailableException e) {
                LOG.warn("Timing reference fetching stopped: {}", e.getMessage());
                stopped = true;
            } catch (RuntimeException e) {
                LOG.warn("Could not fetch a timing reference for edition {}: {}", target.editionId(),
                    e.getClass().getSimpleName());
            }
            try {
                written += measure(target, shared.stored);
            } catch (RuntimeException e) {
                LOG.warn("Could not measure the forced subtitles of edition {}: {}", target.editionId(),
                    e.getClass().getSimpleName());
            }
        }
        return written;
    }

    /**
     * Walks the candidates in order, fetching those not stored while the run, the day and the film's cap allow, until
     * one is usable and matches. A reference fetched joins the film's shared map; one already stored costs nothing.
     */
    private void fetchFor(Target target, Shared shared, int checkLimit, int[] fetchesLeft) {
        for (OpenSubtitlesResult candidate : target.candidates()) {
            OpenSubtitlesReference reference = shared.stored.get(candidate.fileId());
            boolean fetched = reference == null || !reference.isFinal();
            if (fetched) {
                if (fetchesLeft[0] <= 0 || reference == null && shared.kept >= TimingReferenceRule.MAX_REFERENCES) {
                    return;
                }
                boolean added = reference == null;
                try {
                    reference = fetcher.fetch(target.filmId(), target.imdbId(), candidate, checkLimit);
                } catch (OpenSubtitlesChargedException e) {
                    // The account was charged: the download counts against the day (it is an
                    // OpenSubtitlesUnavailableException, so run() stops fetching).
                    counted();
                    throw e;
                }
                // A reference another writer stored mid-run is counted against the timing allowance too, on purpose:
                // ReferenceFetcher does not say whether it spent a download.
                fetchesLeft[0]--;
                counted();
                shared.kept += added ? 1 : 0;
                shared.stored.put(candidate.fileId(), reference);
            }
            Judgement judgement = judge(target, reference);
            if (fetched) {
                LOG.info("Timing reference for edition {} (film {}): fetched file {} (\"{}\", hearing-impaired {}), "
                        + "{} lines, usable {}, best match {}% of a forced file's lines", target.editionId(),
                    target.filmId(), candidate.fileId(), candidate.release(), candidate.hearingImpaired(),
                    reference.getCueCount(), isUsable(target, reference),
                    Math.round(judgement.bestShare() * PERCENT));
            }
            if (judgement.matches()) {
                return;
            }
        }
    }

    private static boolean isUsable(Target target, OpenSubtitlesReference reference) {
        return TimingReferenceRule.usable(reference, target.runtimeMs(), target.creditsStartMs());
    }

    /** Whether a forced file under 300 lines (one under 8 is TOO_FEW and never matches) matches; the best share. */
    private static Judgement judge(Target target, OpenSubtitlesReference reference) {
        if (!isUsable(target, reference)) {
            return new Judgement(false, 0);
        }
        List<Long> referenceStarts = starts(SrtParser.cues(reference.getCuesText()));
        boolean matches = false;
        double best = 0;
        for (File file : target.files()) {
            // Only a file under 300 lines decides the walk: a full-length one would match any full reference.
            if (file.lines() >= TimingReferenceRule.FULL_LINES) {
                continue;
            }
            Result fit = TimingFit.fit(starts(SrtParser.cues(file.cuesText())), referenceStarts);
            matches |= TimingReferenceRule.matches(fit);
            best = Math.max(best, fit.matchedShare());
        }
        return new Judgement(matches, best);
    }

    /**
     * Writes the line of each forced file not measured yet against each usable stored reference of the edition
     * ({@link TimingReferenceRule#references}: the candidates' first, then any other stored full file of the film), in
     * that order. A file whose cues have changed is measured again; returns how many lines were written.
     */
    private int measure(Target target, Map<Long, OpenSubtitlesReference> stored) {
        Map<Long, OpenSubtitlesResult> candidates = new HashMap<>();
        target.candidates().forEach(c -> candidates.putIfAbsent(c.fileId(), c));
        int written = 0;
        for (OpenSubtitlesReference reference : TimingReferenceRule.references(target.candidates(), stored,
            OpenSubtitlesReference::getCueCount)) {
            if (isUsable(target, reference)) {
                written += measure(target, candidates.get(reference.getOsFileId()), reference);
            }
        }
        return written;
    }

    /** Measures against one reference; {@code candidate} is null for a stored file that is not a current candidate. */
    private int measure(Target target, OpenSubtitlesResult candidate, OpenSubtitlesReference reference) {
        List<Long> referenceStarts = null;
        int written = 0;
        for (File file : target.files()) {
            String cues = JsonEtag.etag(file.cuesText().getBytes(StandardCharsets.UTF_8));
            if (!done.add(file.sidecarId() + ":" + cues + ":" + reference.getOsFileId())) {
                continue;
            }
            if (referenceStarts == null) {
                referenceStarts = starts(SrtParser.cues(reference.getCuesText()));
            }
            Result fit = TimingFit.fit(starts(SrtParser.cues(file.cuesText())), referenceStarts);
            LOG.info("Forced timing reference of edition {} (film {}): {} file {} ({} lines, {}) against "
                    + "OpenSubtitles file {} (\"{}\", fps {}, hearing-impaired {}, {} lines); {} at {} ms, {}% of {} "
                    + "lines matched, median residual {} ms; reference's last cue ends {}, runtime {}, credits "
                    + "start {}",
                target.editionId(), target.filmId(), file.origin(), file.sidecarId(), file.lines(),
                file.lines() >= TimingReferenceRule.FULL_LINES ? "300 or more" : "under 300", reference.getOsFileId(),
                candidate == null ? reference.getRelease() : candidate.release(),
                candidate == null || candidate.fps() == null ? "unknown" : candidate.fps(),
                candidate == null ? "unknown" : candidate.hearingImpaired(), reference.getCueCount(),
                fit.verdict(), fit.fittedOffsetMs(), Math.round(fit.matchedShare() * PERCENT), fit.servedCount(),
                fit.medianResidualMs(),
                FullFitProbe.clock(TimingReferenceRule.lastCueEnd(reference)), FullFitProbe.clock(target.runtimeMs()),
                target.creditsStartMs() == null ? "unknown" : FullFitProbe.clock(target.creditsStartMs()));
            written++;
        }
        return written;
    }

    private static List<Long> starts(List<SubtitleCue> cues) {
        return cues.stream().map(SubtitleCue::startMs).toList();
    }
}
