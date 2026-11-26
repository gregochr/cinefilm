package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.FullFitService.Job;
import gregochr.cinefilm.subtitle.TimingFit.Result;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The measurement of phase 5d-1: for each full download, compares it with a second, different OpenSubtitles full file
 * with {@link TimingFit} in both directions (download to reference, then reference to download), and writes one INFO
 * line per edition. <strong>It stores nothing and acts on nothing</strong>: phase 5d-2 sets its thresholds from these
 * lines (about ten films).
 *
 * <p>The second file is a reference already stored for the film (free), else the first other candidate for the
 * edition's cut, fetched through {@link ReferenceFetcher} (its one lock, its stored-never-twice rule and the
 * comparison ceiling it is given: {@link DownloadShares#forChecks}). At most one file is fetched per download. A
 * download is measured once per process (an in-memory set); one that has no second file at all is noted once, and so
 * is a pair the rate rule can never judge (a rate conflict, or a retimed file with no original cues). A pair with no
 * verdict for want of a rate is tried again on every pass, silently after the first: its "no verdict" line is written
 * once per process, until the rate arrives and it is measured. A spent budget or an OpenSubtitles limit ends the pass
 * and the download is measured on a later one. Run by
 * {@link SidecarCheckRefresher} in its check job; nothing here throws a budget stop or an outage to its caller.
 */
final class FullFitProbe {

    private static final Logger LOG = LoggerFactory.getLogger(FullFitProbe.class);
    private static final long MS_PER_SECOND = 1000;
    private static final long SECONDS_PER_MINUTE = 60;
    private static final long MINUTES_PER_HOUR = 60;
    private static final int PERCENT = 100;

    private final ReferenceFetcher fetcher;
    private final FullFitService service;
    private final FilmRates filmRates;
    private final Set<Long> done = ConcurrentHashMap.newKeySet();
    /** The sidecars whose "no verdict for want of a rate" line has been written: it is written once, not every pass. */
    private final Set<Long> noted = ConcurrentHashMap.newKeySet();
    /** The sidecars a new reference has been fetched for: at most one fetch per download per process. */
    private final Set<Long> fetched = ConcurrentHashMap.newKeySet();

    /** Creates the probe over the check job's fetcher, so every fetch goes through its lock and ceilings. */
    FullFitProbe(ReferenceFetcher fetcher, FullFitService service, FilmRates filmRates) {
        this.fetcher = fetcher;
        this.service = service;
        this.filmRates = filmRates;
    }

    /**
     * Forgets that some sidecars were measured, so the next pass measures them again (their cues changed).
     *
     * @param sidecarIds the sidecars to forget
     */
    void forget(Collection<Long> sidecarIds) {
        done.removeAll(sidecarIds);
        noted.removeAll(sidecarIds);
        fetched.removeAll(sidecarIds);
    }

    /**
     * Measures the full downloads not measured yet by this process.
     *
     * @param checkLimit the most downloads the comparisons may bring today's total to
     * @return how many lines were written
     */
    int measure(int checkLimit) {
        int measured = 0;
        for (Job job : service.due(done)) {
            try {
                if (measure(job, checkLimit)) {
                    measured++;
                }
            } catch (ReferenceFetcher.Stop e) {
                LOG.info("Full subtitle measurement stopped: {}", e.getMessage());
                break;
            } catch (OpenSubtitlesUnavailableException e) {
                LOG.warn("Full subtitle measurement stopped: {}", e.getMessage());
                break;
            } catch (RuntimeException e) {
                LOG.warn("Could not measure the full subtitle of edition {}: {}", job.editionId(),
                    e.getClass().getSimpleName());
            }
        }
        return measured;
    }

    /** What the references tried for one download came to. */
    private static final class Tried {
        private int references;
        private boolean allPermanent = true;
    }

    /**
     * Measures a download against the first stored full reference the rate rule can judge, trying each in order (a
     * rateless final row is filled first). Only when none gives a verdict is one new reference fetched (once per
     * download), and judged the same way. A reference with a rate conflict or an unknown rate is passed over.
     */
    private boolean measure(Job job, int checkLimit) {
        if (lacksOriginal(job.rateSide()) && !job.others().isEmpty()) {
            // Nothing can be judged against it, whatever the references: permanent, so no walk and no fetch.
            done.add(job.sidecarId());
            LOG.info("Full subtitle fit of edition {} (film {}): not measured, no verdict: {} (sidecar {})",
                job.editionId(), job.filmId(), RateComparison.Reason.UNKNOWN_RATE, job.sidecarId());
            return false;
        }
        Tried tried = new Tried();
        for (OpenSubtitlesResult candidate : job.others()) {
            OpenSubtitlesReference stored = job.stored().get(candidate.fileId());
            if (stored != null && isFull(stored)) {
                fillRate(stored, candidate);
                if (judge(job, stored, tried)) {
                    return true;
                }
            }
        }
        OpenSubtitlesReference extra = fetchOne(job, checkLimit);
        if (extra != null && judge(job, extra, tried)) {
            return true;
        }
        if (tried.references == 0) {
            done.add(job.sidecarId());
            LOG.info("Full subtitle fit of edition {} (film {}): not measured, OpenSubtitles has no second full file "
                + "to compare with", job.editionId(), job.filmId());
        } else if (tried.allPermanent) {
            // Indexing changes none of them: there is nothing to wait for.
            done.add(job.sidecarId());
        }
        return false;
    }

    /** Judges the download against one reference and writes the line; false (and a line, once) when no verdict. */
    private boolean judge(Job job, OpenSubtitlesReference second, Tried tried) {
        RateComparison.Side referenceSide = RateComparison.Side.of(second);
        RateComparison.Judged<Result> judged = RateComparison.judge(job.rateSide(), referenceSide,
            () -> filmRates.forEdition(job.editionId()), job.download(), RateComparison.originalOf(job.rateSide()),
            SrtParser.cues(second.getCuesText()), RateComparison.originalOf(referenceSide),
            (a, b) -> TimingFit.fit(starts(a), starts(b)), r -> TimingFit.Verdict.CHECK.equals(r.verdict()),
            r -> TimingFit.Verdict.ALIGNED.equals(r.verdict()));
        if (!judged.hasVerdict()) {
            // Indexing never changes a conflict or a missing original; any other unknown rate may be settled later.
            tried.references++;
            if (judged.reason() != RateComparison.Reason.RATE_CONFLICT && !lacksOriginal(job.rateSide())
                && !lacksOriginal(referenceSide)) {
                tried.allPermanent = false;
            }
            if (noted.add(job.sidecarId())) {
                LOG.info("Full subtitle fit of edition {} (film {}): not measured, no verdict: {} (sidecar {}, "
                    + "reference {})", job.editionId(), job.filmId(), judged.reason(), job.sidecarId(),
                    second.getId());
            }
            return false;
        }
        done.add(job.sidecarId());
        List<SubtitleCue> download = judged.first();
        List<SubtitleCue> reference = judged.second();
        Result forward = judged.result();
        Result backward = TimingFit.fit(starts(reference), starts(download));
        LOG.info("Full subtitle fit of edition {} (film {}): download file {} ({} cues, last cue {}) against "
                + "reference file {} ({} cues, last cue {}); download to reference {}; reference to download {}; "
                + "runtime {}; credits start {}",
            job.editionId(), job.filmId(), job.downloadFileId(), download.size(), lastCue(download),
            second.getOsFileId(), reference.size(), lastCue(reference), describe(forward), describe(backward),
            clock(job.runtimeMs()), job.creditsStartMs() == null ? "unknown" : clock(job.creditsStartMs()));
        return true;
    }

    /**
     * A final row stored before rates were kept gets the candidate's stated one (the fetcher's own fill, no
     * download), or the rate rule would reject it as of unknown rate. A failed fill leaves the row unrated.
     */
    private void fillRate(OpenSubtitlesReference stored, OpenSubtitlesResult candidate) {
        try {
            fetcher.fillRate(stored, candidate);
        } catch (RuntimeException e) {
            LOG.warn("Could not fill the stated frame rate of OpenSubtitles file {}: {}", candidate.fileId(),
                e.getClass().getSimpleName());
        }
    }

    /**
     * The first other candidate not stored yet, fetched within the ceiling, at most once per download per process;
     * null when there is none, when it has been tried, or when it came back not full (the one try this download gets).
     */
    private OpenSubtitlesReference fetchOne(Job job, int checkLimit) {
        if (fetched.contains(job.sidecarId())) {
            return null;
        }
        for (OpenSubtitlesResult candidate : job.others()) {
            OpenSubtitlesReference known = job.stored().get(candidate.fileId());
            // A final reference was judged above (usable and full) or is spent (permanent failure, not full); one that
            // is not final is a charged transient failure the fetcher retries, and which throws again while it fails.
            if (known == null || !known.isFinal()) {
                OpenSubtitlesReference got = fetcher.fetch(job.filmId(), job.imdbId(), candidate, checkLimit);
                fetched.add(job.sidecarId());
                return isFull(got) ? got : null;
            }
        }
        return null;
    }

    /** True for a retimed side with no original cues to compare through. */
    private static boolean lacksOriginal(RateComparison.Side side) {
        return side.retimed() && side.originalCuesText() == null;
    }

    private static boolean isFull(OpenSubtitlesReference reference) {
        return DownloadPlan.promotable(reference, SubtitleKind.FULL);
    }

    private static List<Long> starts(List<SubtitleCue> cues) {
        return cues.stream().map(SubtitleCue::startMs).toList();
    }

    private static String describe(Result result) {
        long percent = Math.round(result.matchedShare() * PERCENT);
        return result.verdict() + " at " + result.fittedOffsetMs() + " ms, " + percent + "% of "
            + result.servedCount() + " lines matched";
    }

    private static String lastCue(List<SubtitleCue> cues) {
        return clock(cues.stream().mapToLong(SubtitleCue::endMs).max().orElse(0));
    }

    /** A time as {@code h:mm:ss}. */
    static String clock(long ms) {
        long seconds = Math.max(0, ms) / MS_PER_SECOND;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR),
            seconds / SECONDS_PER_MINUTE % MINUTES_PER_HOUR, seconds % SECONDS_PER_MINUTE);
    }
}
