package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.library.LibraryLock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database side of {@link SubtitleRetimeJob} (docs/engineering/subtitle-frame-rate-plan.md, "The job"): puts one
 * downloaded subtitle on the film's timeline, restores it, or decides whether it may be retimed at all. Every call
 * takes the library lock the Admin's edits take, reads the row again under it and checks {@code retime}, the offset
 * source and the verification again before it writes, so an owner's edit made at the same moment is never
 * overwritten; a busy lock leaves the row for the next run.
 *
 * <p>k is worked out again on each call from the original cues ({@link FrameRateRetime}, the film's rate from
 * {@link FilmRates}), so a stored original is never scaled twice. A rewrite or a restore also resets what was set on
 * the cues that are no longer there (an automatic or the owner's offset, the owner's verification), keeps a failing
 * check or fit of the file (re-keyed to the new cues and marked outdated, so it stays due) and takes a proving one
 * away (an {@code AGREES} check or an {@code ALIGNED} fit proves nothing about other cues, and must not come back if
 * the original is restored).
 */
@Service
public class SubtitleRetimeService {

    /** What a call did. */
    public enum Outcome {
        /** The cues were rewritten, scaled by a new k. */
        REWRITTEN,
        /** The original cues were put back. */
        RESTORED,
        /** The owner's hand-set timing was found, so retime was set off for the file. */
        DECIDED_OFF,
        /** Nothing needed to change. */
        UNCHANGED,
        /** The library lock was taken by someone else; the row is left for the next run. */
        BUSY
    }

    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;
    private final FilmRates rates;
    private final ForcedDownloadCheckRepository downloadChecks;
    private final ForcedSubtitleAlignmentRepository alignments;
    private final LibraryLock lock;
    private final Clock clock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public SubtitleRetimeService(SubtitleSidecarRepository sidecars, EditionRepository editions, FilmRates rates,
                                 ForcedDownloadCheckRepository downloadChecks,
                                 ForcedSubtitleAlignmentRepository alignments, LibraryLock lock, Clock clock,
                                 PlatformTransactionManager transactions) {
        this.sidecars = sidecars;
        this.editions = editions;
        this.rates = rates;
        this.downloadChecks = downloadChecks;
        this.alignments = alignments;
        this.lock = lock;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The ids of the downloads to visit: every download with a known stated rate, and every one that is retimed. */
    public List<Long> candidateIds() {
        return sidecars.findRetimeCandidateIds();
    }

    /**
     * Visits one download.
     *
     * @param id the download's row id
     * @param mode {@link RetimeMode#ON} or {@link RetimeMode#RESTORE}; with {@link RetimeMode#OFF} nothing is read
     *     or written
     * @return what was done
     */
    public Outcome process(Long id, RetimeMode mode) {
        if (mode == RetimeMode.OFF) {
            return Outcome.UNCHANGED;
        }
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            return Outcome.BUSY;
        }
        try {
            return tx.execute(status -> processLocked(id, mode));
        } finally {
            lock.release();
        }
    }

    /**
     * Applies the owner's per-file choice for a download, under the caller's library lock and transaction (the Admin's
     * retime route holds both). {@code retime} is set to {@code OFF} (a retimed file is restored at once) or to
     * {@code AUTO} (the file is retimed at once when k is not 1 and both rates are known), whatever the global
     * {@code cinefilm.subtitles.retime} setting says: it is the owner's own choice. The row is the caller's managed
     * entity, so its change is written with the caller's transaction, before the lock is given back and the job reads
     * it again.
     *
     * @param row the download, already read under the lock
     * @param on true to retime it, false to leave it as it was timed
     * @return what was done
     */
    public Outcome applyChoice(SubtitleSidecar row, boolean on) {
        if (!on && row.getRetime() == RetimeChoice.OFF_FITS) {
            // The file already fits this copy as it is: "don't retime" has nothing to add, and nothing would set
            // OFF_FITS back once it was overwritten.
            return Outcome.UNCHANGED;
        }
        row.setRetime(on ? RetimeChoice.AUTO : RetimeChoice.OFF);
        return processLocked(row.getId(), RetimeMode.ON);
    }

    private Outcome processLocked(Long id, RetimeMode mode) {
        // Read again under the lock: what the candidate list said may be an edit ago.
        SubtitleSidecar row = sidecars.findById(id).orElse(null);
        if (row == null || !row.isDownload()) {
            return Outcome.UNCHANGED;
        }
        boolean retimed = row.getRetimedBy() != null;
        // Null only when an edit deleted the edition the download was made for.
        Edition edition = edition(row);
        if (mode == RetimeMode.RESTORE || row.getRetime() == RetimeChoice.OFF
            || row.getRetime() == RetimeChoice.OFF_FITS) {
            return retimed ? restore(row, edition) : Outcome.UNCHANGED;
        }
        Double film = edition == null ? null : rates.forEdition(edition.getId());
        if (film == null || row.getTimedFps() == null) {
            // A rate not known for now: a retimed row is left as it is.
            return Outcome.UNCHANGED;
        }
        double k = FrameRateRetime.factor(row.getTimedFps(), film);
        if (row.getRetime() == null) {
            if (k == 1.0) {
                return Outcome.UNCHANGED;
            }
            if (timingSetByHand(row)) {
                row.setRetime(RetimeChoice.OFF);
                sidecars.save(row);
                return Outcome.DECIDED_OFF;
            }
            // Wait for the decision on a film with an English track of its own: 2a-3b tries a fit of the original
            // cues first, and a file that fits as it is must not have been rewritten before that.
            if (hasEnglishTrack(edition)) {
                return Outcome.UNCHANGED;
            }
            row.setRetime(RetimeChoice.AUTO);
        }
        if (k == 1.0) {
            return retimed ? restore(row, edition) : Outcome.UNCHANGED;
        }
        if (retimed && Double.compare(row.getRetimedBy(), k) == 0) {
            return Outcome.UNCHANGED;
        }
        return rewrite(row, edition, k, film);
    }

    /**
     * True when the owner set the row's timing by hand (an OWNER offset or a verification): the job then decides
     * {@code retime} off for it and never retimes it.
     *
     * @param row the download
     * @return whether its timing is the owner's
     */
    static boolean timingSetByHand(SubtitleSidecar row) {
        return row.getOffsetSource() == SubtitleSidecar.OffsetSource.OWNER || row.getOwnerVerifiedAt() != null;
    }

    private Outcome rewrite(SubtitleSidecar row, Edition edition, double k, double film) {
        // The original is kept once: never scale a retimed file twice.
        String original = row.getOriginalCuesText() != null ? row.getOriginalCuesText() : row.getCuesText();
        row.setOriginalCuesText(original);
        row.setRetimedBy(k);
        row.setRetimedForFps(film);
        changed(row, edition, CueScaler.scale(original, k));
        return Outcome.REWRITTEN;
    }

    private Outcome restore(SubtitleSidecar row, Edition edition) {
        String original = row.getOriginalCuesText();
        row.setOriginalCuesText(null);
        row.setRetimedBy(null);
        row.setRetimedForFps(null);
        changed(row, edition, original);
        return Outcome.RESTORED;
    }

    /** The write that follows a rewrite or a restore, in the one transaction. */
    private void changed(SubtitleSidecar row, Edition edition, String cues) {
        Instant now = clock.instant();
        // What a check or fit of the cues as they were is keyed to: read before the write changes the cues and offset.
        String oldVersion = edition == null ? null : ServedSubtitles.version(edition, Downloads.pick(row));
        String oldIdentity = TimingKeys.identity(row);
        row.setCuesText(cues);
        row.setLastEndMs(SrtParser.cues(cues).stream().mapToLong(SubtitleCue::endMs).max().orElse(0));
        // The fit, the owner's nudge and the owner's check were all made on cues that are no longer there.
        row.setOffsetMs(0);
        row.setOffsetSource(null);
        row.clearVerification();
        row.setUpdatedAt(now);
        sidecars.save(row);
        carryChecks(row, edition, now, oldVersion, oldIdentity);
    }

    /**
     * A failing check or fit that was current for the cues as they were is kept for the new cues, marked outdated so it
     * is due again; one that was already not current (the owner nudged the offset, the parts changed) is left as it is,
     * so an old verdict never decides again. A proving one is removed (it would otherwise prove the old cues again if
     * they came back).
     */
    private void carryChecks(SubtitleSidecar row, Edition edition, Instant now, String oldVersion,
                             String oldIdentity) {
        String version = edition == null ? null : ServedSubtitles.version(edition, Downloads.pick(row));
        downloadChecks.findBySidecarId(row.getId()).ifPresent(check -> {
            if (version == null || SubtitleComparison.Verdict.AGREES.name().equals(check.getVerdict())) {
                downloadChecks.delete(check);
            } else if (check.getServedVersion().equals(oldVersion)) {
                check.setServedVersion(version);
                check.setStaleSince(now);
                downloadChecks.save(check);
            }
        });
        alignments.findBySidecarId(row.getId()).ifPresent(fit -> {
            if (fit.getVerdict() == TimingFit.Verdict.ALIGNED) {
                alignments.delete(fit);
            } else if (fit.getSidecarIdentity().equals(oldIdentity)) {
                fit.setSidecarIdentity(TimingKeys.identity(row));
                fit.setStaleSince(now);
                alignments.save(fit);
            }
        });
    }

    private Edition edition(SubtitleSidecar row) {
        return editions.findWithFilesByIdIn(Set.of(Downloads.madeForEditionId(row))).stream().findFirst()
            .orElse(null);
    }

    private static boolean hasEnglishTrack(Edition edition) {
        return edition.originalParts().stream()
            .flatMap(part -> part.getSubtitleStreams().stream())
            .anyMatch(stream -> !stream.isForced() && stream.getLanguage() != null
                && gregochr.cinefilm.film.SubtitleStream.isEnglishOrUnlabelled(stream.getLanguage()));
    }
}
