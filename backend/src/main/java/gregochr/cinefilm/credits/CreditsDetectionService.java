package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsDetectionRepository.Stored;
import gregochr.cinefilm.credits.CreditsFrameReader.TimedFrame;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.playback.PlaybackStateRepository;
import gregochr.cinefilm.subtitle.EmbeddedCueStarts;
import gregochr.cinefilm.subtitle.EmbeddedCueStartsRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database side of credits detection, and the analysis of one file: which files are due, running the frame reader
 * and the pure finder, and saving what they found. Kept apart from {@link CreditsRefresher} (which decides when to run
 * and when to wait) so each can be tested alone.
 *
 * <p>Only the last original part of an edition is analysed: credits are only ever at the end of the last part, and part
 * 1's end (a fade, an intermission card) must never produce a result. The result is stored against the file, with its
 * fingerprint, so a changed file is analysed again. Nothing here changes an edition, the player or the watched rule.
 */
@Service
public class CreditsDetectionService {

    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final CreditsDetectionRepository detections;
    private final EmbeddedCueStartsRepository cueStarts;
    private final PlaybackStateRepository states;
    private final CreditsFrameReader reader;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /**
     * A file to analyse.
     *
     * @param mediaFileId the file
     * @param path its path relative to the library root
     * @param fingerprint its size and modification time as last scanned
     * @param durationMs its length on its timeline
     * @param title the film's title, for the log
     */
    public record DueFile(Long mediaFileId, String path, String fingerprint, long durationMs, String title) {
    }

    /** What {@link #save} did. */
    public enum SaveOutcome {
        /** The result was stored. */
        SAVED,
        /** The file changed or went while it was analysed: nothing was stored, it is due again. */
        DROPPED,
        /** A scan or an edit holds the library lock: nothing was stored, try again later. */
        BUSY
    }

    /** Creates the service. */
    public CreditsDetectionService(EditionRepository editions, MediaFileRepository files,
                                   CreditsDetectionRepository detections, EmbeddedCueStartsRepository cueStarts,
                                   PlaybackStateRepository states, CreditsFrameReader reader, LibraryLock lock,
                                   PlatformTransactionManager transactions) {
        this.editions = editions;
        this.files = files;
        this.detections = detections;
        this.cueStarts = cueStarts;
        this.states = states;
        this.reader = reader;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * The files to analyse: the last original part of every edition that is present and playable, has a fingerprint
     * and a duration, and either has no stored result or has one for a different fingerprint. Part 1 of a split
     * edition is never due. Editions in progress (a position past 0, not removed from Continue watching) come first,
     * then the most recently added files. Three queries in all, however many films there are.
     *
     * @return the files in the order to work on them
     */
    @Transactional(readOnly = true)
    public List<DueFile> dueFiles() {
        Map<Long, String> done = new HashMap<>();
        for (Stored stored : detections.findAllStored()) {
            done.put(stored.mediaFileId(), stored.fingerprint());
        }
        Set<Long> inProgress = new HashSet<>(states.findInProgressEditionIds());
        record Candidate(DueFile due, boolean inProgress, Instant addedAt) {
        }
        List<Candidate> due = new ArrayList<>();
        for (Edition edition : editions.findAllWithFiles()) {
            List<MediaFile> parts = edition.originalParts();
            MediaFile last = parts.isEmpty() ? null : parts.getLast();
            if (last == null || !analysable(last)) {
                continue;
            }
            String fingerprint = last.fingerprint();
            if (fingerprint.equals(done.get(last.getId()))) {
                continue;
            }
            due.add(new Candidate(new DueFile(last.getId(), last.getPath(), fingerprint, last.timelineDurationMs(),
                edition.getFilm().getTitle()), inProgress.contains(edition.getId()), last.getAddedAt()));
        }
        return due.stream()
            .sorted(Comparator.comparing((Candidate c) -> !c.inProgress())
                .thenComparing(Candidate::addedAt, Comparator.reverseOrder())
                .thenComparing(c -> c.due().mediaFileId(), Comparator.reverseOrder()))
            .map(Candidate::due).toList();
    }

    /** A file the analysis can read: present, playable, a known change marker and a known length. */
    private static boolean analysable(MediaFile file) {
        Long duration = file.timelineDurationMs();
        return file.getPresence() == Presence.PRESENT && file.getPlayability() == Playability.PLAYABLE
            && file.fingerprint() != null && duration != null && duration > 0;
    }

    /**
     * Analyses one file: five frames for the picture area, one every 5 seconds across the window to find a candidate,
     * and, when there is one, a dense decode around it to place the start. The cue starts of the film's full English
     * track are used when the file has a current cached read of them; no subtitle track is ever read here.
     *
     * @param due the file
     * @param file the file on disk (an absolute path under the library root)
     * @param checkpoint run before every ffmpeg call; it throws to stop the analysis
     * @return the result
     * @throws CreditsFrameReader.ReadException when a frame cannot be read (the whole file is abandoned)
     */
    public CreditsResult detect(DueFile due, Path file, Runnable checkpoint) {
        List<Long> cues = cachedCueStarts(due);
        CreditsFrameReader.Job job = reader.open(file, due.durationMs(), checkpoint);
        try {
            ActiveArea area = ActiveArea.of(job.activeAreaFrames());
            List<TimedFrame> coarse = job.coarseFrames();
            int peak = PeakLuma.of(coarse.stream().map(TimedFrame::frame).toList(), area);
            List<CoarseSample> samples = coarse.stream()
                .map(c -> new CoarseSample(c.offsetMs(), FrameFeatures.measure(c.frame(), area, peak).frameClass()))
                .toList();
            CoarseOutcome outcome = CreditsFinder.findCandidate(samples, due.durationMs());
            if (outcome instanceof CoarseOutcome.Rejected rejected) {
                return rejected.result();
            }
            CoarseOutcome.Candidate candidate = (CoarseOutcome.Candidate) outcome;
            return CreditsFinder.refine(candidate, job.denseFrames(candidate.startMs()), area, peak, cues);
        } finally {
            job.close();
        }
    }

    /** The cue starts of the file's cached read, or null when there is no current usable one. */
    private List<Long> cachedCueStarts(DueFile due) {
        return cueStarts.findById(due.mediaFileId())
            .filter(row -> row.getStatus() == EmbeddedCueStarts.Status.USABLE
                && due.fingerprint().equals(row.getFingerprint()))
            .map(EmbeddedCueStarts::getStartsMs).orElse(null);
    }

    /**
     * Stores a result, but only while the file is still the one that was analysed: it exists, is present and has the
     * same fingerprint. The check and the write are one step under the {@link LibraryLock} (a scan rewrites the file
     * rows), taken only for this short write, never while ffmpeg runs.
     *
     * @param due the file as it was when analysed
     * @param result what was found
     * @param now the time to stamp
     * @return {@code SAVED}; {@code DROPPED} when the file changed or went meanwhile; {@code BUSY} when a scan or an
     *     edit holds the lock (the result is lost and the file is analysed again later)
     */
    public SaveOutcome save(DueFile due, CreditsResult result, Instant now) {
        if (!lock.tryAcquire(LibraryLock.CREDITS)) {
            return SaveOutcome.BUSY;
        }
        try {
            return tx.execute(status -> saveLocked(due, result, now));
        } finally {
            lock.release();
        }
    }

    private SaveOutcome saveLocked(DueFile due, CreditsResult result, Instant now) {
        MediaFile file = files.findById(due.mediaFileId()).orElse(null);
        if (file == null || file.getPresence() != Presence.PRESENT || !due.fingerprint().equals(file.fingerprint())) {
            return SaveOutcome.DROPPED;
        }
        CreditsDetection row = detections.findById(file.getId()).orElseGet(CreditsDetection::new);
        row.setMediaFileId(file.getId());
        row.setFingerprint(due.fingerprint());
        row.setVerdict(result.verdict());
        row.setOffsetMs(result.offsetMs());
        String reasons = String.join("\n", result.reasons());
        row.setReasons(reasons.length() <= CreditsDetection.MAX_REASONS ? reasons
            : reasons.substring(0, CreditsDetection.MAX_REASONS));
        row.setCheckedAt(now);
        detections.save(row);
        return SaveOutcome.SAVED;
    }
}
