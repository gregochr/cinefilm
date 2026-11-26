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
import java.util.concurrent.atomic.AtomicLong;
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
 * download is measured once per process (an in-memory set); one that has no second file at all is noted once. A spent
 * budget or an OpenSubtitles limit ends the pass and the download is measured on a later one. Run by
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
    private final Set<Long> done = ConcurrentHashMap.newKeySet();
    /** Raised by every {@link #forget}: a measurement that started before it must not mark its sidecar done. */
    private final AtomicLong epoch = new AtomicLong();

    /** Creates the probe over the check job's fetcher, so every fetch goes through its lock and ceilings. */
    FullFitProbe(ReferenceFetcher fetcher, FullFitService service) {
        this.fetcher = fetcher;
        this.service = service;
    }

    /**
     * Forgets that some sidecars were measured, so the next pass measures them again (their cues changed).
     *
     * @param sidecarIds the sidecars to forget
     */
    void forget(Collection<Long> sidecarIds) {
        epoch.incrementAndGet();
        done.removeAll(sidecarIds);
    }

    /**
     * Measures the full downloads not measured yet by this process.
     *
     * @param checkLimit the most downloads the comparisons may bring today's total to
     * @return how many lines were written
     */
    int measure(int checkLimit) {
        int measured = 0;
        // Before the due list is read: a forget that lands after this, whenever, keeps its sidecar from being marked.
        long started = epoch.get();
        for (Job job : service.due(done)) {
            try {
                if (measure(job, checkLimit, started)) {
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

    /** Marks a sidecar measured unless a forget landed since the measurement started (it was on the old cues). */
    boolean markUnlessForgotten(Set<Long> marks, Long sidecarId, long started) {
        if (epoch.get() != started) {
            return true;
        }
        boolean added = marks.add(sidecarId);
        if (epoch.get() != started) {
            // A forget landed between the check and the add: it did not see this mark, so it is taken back.
            marks.remove(sidecarId);
            return true;
        }
        return added;
    }

    private boolean measure(Job job, int checkLimit, long started) {
        if (job.rateSide().retimed()) {
            // A retimed download is measured from phase 2b: nothing is fetched or judged for it before then.
            markUnlessForgotten(done, job.sidecarId(), started);
            LOG.info("Full subtitle fit: sidecar {} not measured: retimed, measured from phase 2b", job.sidecarId());
            return false;
        }
        OpenSubtitlesReference second = second(job, checkLimit);
        markUnlessForgotten(done, job.sidecarId(), started);
        if (second == null) {
            LOG.info("Full subtitle fit of edition {} (film {}): not measured, OpenSubtitles has no second full file "
                + "to compare with", job.editionId(), job.filmId());
            return false;
        }
        if (second.getRetimedBy() != null) {
            // A raw download against a reference already on the film's timeline would drift: passed over, like a
            // retimed download, rather than judged by a rate rule the probe does not carry.
            LOG.info("Full subtitle fit: sidecar {} not measured: the second file {} is retimed",
                job.sidecarId(), second.getOsFileId());
            return false;
        }
        List<SubtitleCue> reference = SrtParser.cues(second.getCuesText());
        Result forward = TimingFit.fit(starts(job.download()), starts(reference));
        Result backward = TimingFit.fit(starts(reference), starts(job.download()));
        LOG.info("Full subtitle fit of edition {} (film {}): download file {} ({} cues, last cue {}) against "
                + "reference file {} ({} cues, last cue {}); download to reference {}; reference to download {}; "
                + "runtime {}; credits start {}",
            job.editionId(), job.filmId(), job.downloadFileId(), job.download().size(), lastCue(job.download()),
            second.getOsFileId(), reference.size(), lastCue(reference), describe(forward), describe(backward),
            clock(job.runtimeMs()), job.creditsStartMs() == null ? "unknown" : clock(job.creditsStartMs()));
        return true;
    }

    /**
     * The second file: a full reference already stored for the film (best candidate first), else the first other
     * candidate not stored yet, fetched within the ceiling. A candidate stored as unusable or not full is passed over.
     * Null when there is none: a candidate that came back not full is the one try this download gets.
     */
    private OpenSubtitlesReference second(Job job, int checkLimit) {
        for (OpenSubtitlesResult candidate : job.others()) {
            OpenSubtitlesReference stored = job.stored().get(candidate.fileId());
            if (stored != null && isFull(stored)) {
                return stored;
            }
        }
        for (OpenSubtitlesResult candidate : job.others()) {
            OpenSubtitlesReference known = job.stored().get(candidate.fileId());
            // A final reference was judged above (usable and full) or is spent (permanent failure, not full); one that
            // is not final is a charged transient failure the fetcher retries, and which throws again while it fails.
            if (known == null || !known.isFinal()) {
                OpenSubtitlesReference fetched = fetcher.fetch(job.filmId(), job.imdbId(), candidate, checkLimit);
                return isFull(fetched) ? fetched : null;
            }
        }
        return null;
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
