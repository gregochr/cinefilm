package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database side of {@link SubtitleRetimeJob} for stored references (docs/engineering/subtitle-frame-rate-plan.md,
 * "The stored cues are rewritten, once"): puts one reference on the film's timeline, or restores it. References are
 * kept per film, so one is rewritten only when every original part of every edition of its film has one agreed rate
 * ({@link FilmRates#forFilm}); a film whose editions disagree keeps its references raw, and a retimed one is put back.
 * Every call takes the library lock, reads the row again under it, and works k out again from the original cues.
 *
 * <p>A rewrite or a restore raises the reference's {@code cuesVersion} (so a check or fit that read the old cues drops
 * its save) and marks every check and fit that names the reference, in every edition of the film, outdated
 * ({@code stale_since}): those rows keep deciding what plays until a new verdict replaces them, and are due again.
 * Fits name a reference by its OpenSubtitles file id, which belongs to one film.
 */
@Service
public class ReferenceRetimeService {

    private final OpenSubtitlesReferenceRepository references;
    private final EditionRepository editions;
    private final FilmRates rates;
    private final ForcedSubtitleCheckRepository checks;
    private final ForcedDownloadCheckRepository downloadChecks;
    private final ForcedSubtitleAlignmentRepository alignments;
    private final LibraryLock lock;
    private final Clock clock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public ReferenceRetimeService(OpenSubtitlesReferenceRepository references, EditionRepository editions,
                                  FilmRates rates, ForcedSubtitleCheckRepository checks,
                                  ForcedDownloadCheckRepository downloadChecks,
                                  ForcedSubtitleAlignmentRepository alignments, LibraryLock lock, Clock clock,
                                  PlatformTransactionManager transactions) {
        this.references = references;
        this.editions = editions;
        this.rates = rates;
        this.checks = checks;
        this.downloadChecks = downloadChecks;
        this.alignments = alignments;
        this.lock = lock;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** The ids of the references to visit: every usable one with a stated rate, and every one that is retimed. */
    public List<Long> candidateIds() {
        return references.findRetimeCandidateIds();
    }

    /**
     * Visits one reference.
     *
     * @param id the reference's row id
     * @param mode {@link RetimeMode#ON} or {@link RetimeMode#RESTORE}; with {@link RetimeMode#OFF} nothing is read or
     *     written
     * @return {@code REWRITTEN}, {@code RESTORED}, {@code UNCHANGED}, or {@code BUSY} when the library lock is held
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

    private Outcome processLocked(Long id, RetimeMode mode) {
        // Read again under the lock: what the candidate list said may be an edit ago.
        OpenSubtitlesReference reference = references.findById(id).orElse(null);
        if (reference == null || !reference.isUsable()) {
            return Outcome.UNCHANGED;
        }
        boolean retimed = reference.getRetimedBy() != null;
        if (mode == RetimeMode.RESTORE) {
            return retimed ? restore(reference) : Outcome.UNCHANGED;
        }
        FilmRates.FilmRate film = rates.forFilm(reference.getFilmId());
        if (film.disagree()) {
            return retimed ? restore(reference) : Outcome.UNCHANGED;
        }
        if (film.rate() == null || reference.getTimedFps() == null) {
            // A rate not known for now: a retimed row is left as it is.
            return Outcome.UNCHANGED;
        }
        double k = FrameRateRetime.factor(reference.getTimedFps(), film.rate());
        if (k == 1.0) {
            return retimed ? restore(reference) : Outcome.UNCHANGED;
        }
        if (retimed && Double.compare(reference.getRetimedBy(), k) == 0) {
            return Outcome.UNCHANGED;
        }
        // The original is kept once: never scale a retimed reference twice.
        String original = reference.getOriginalCuesText() != null ? reference.getOriginalCuesText()
            : reference.getCuesText();
        reference.setOriginalCuesText(original);
        reference.setRetimedBy(k);
        reference.setRetimedForFps(film.rate());
        changed(reference, CueScaler.scale(original, k));
        return Outcome.REWRITTEN;
    }

    private Outcome restore(OpenSubtitlesReference reference) {
        String original = reference.getOriginalCuesText();
        reference.setOriginalCuesText(null);
        reference.setRetimedBy(null);
        reference.setRetimedForFps(null);
        changed(reference, original);
        return Outcome.RESTORED;
    }

    /** The write that follows a rewrite or a restore, in the one transaction. */
    private void changed(OpenSubtitlesReference reference, String cues) {
        reference.replaceCues(cues);
        reference.setLastCueEndMs(SrtParser.cues(cues).stream().mapToLong(SubtitleCue::endMs).max().orElse(0));
        references.save(reference);
        Instant now = clock.instant();
        List<ForcedSubtitleCheck> fileChecks = checks.findByReferenceId(reference.getId());
        fileChecks.forEach(check -> check.setStaleSince(now));
        checks.saveAll(fileChecks);
        List<ForcedDownloadCheck> downloads = downloadChecks.findByReferenceId(reference.getId());
        downloads.forEach(check -> check.setStaleSince(now));
        downloadChecks.saveAll(downloads);
        List<ForcedSubtitleAlignment> fits = alignments.findByReferenceSourceAndReferenceOsFileId(
            ReferenceSource.OPENSUBTITLES, reference.getOsFileId());
        fits.forEach(fit -> fit.setStaleSince(now));
        alignments.saveAll(fits);
    }

    /** Every edition of the films of the given references, in film and edition id order, each edition once. */
    public List<TouchedEdition> touchedEditions(Set<Long> referenceIds) {
        return tx.execute(status -> {
            Set<Long> filmIds = references.findAllById(referenceIds).stream()
                .map(OpenSubtitlesReference::getFilmId).collect(Collectors.toSet());
            return editions.findWithFilesByFilmIdIn(filmIds).stream()
                .sorted(Comparator.comparing((Edition e) -> e.getFilm().getId()).thenComparing(Edition::getId))
                .map(e -> new TouchedEdition(e.getId(), e.getFilm().getId())).toList();
        });
    }
}
