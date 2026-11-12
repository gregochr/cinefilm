package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database side of downloading forced subtitles: which editions are due ({@link DownloadPlan}), and saving a
 * downloaded or promoted OpenSubtitles file as the edition's download. Kept apart from {@link ForcedDownloadRefresher}
 * (which talks to OpenSubtitles) so each can be tested alone.
 *
 * <p>A download is a {@link SubtitleSidecar} row of origin {@code OPENSUBTITLES}: stored against the edition's part 1
 * as a whole-edition file, with the cues of the stored reference, so precedence, the served version, the comparison and
 * the timing fit treat it like the owner's files. It is added under the library lock (a scan and Admin's edits rewrite
 * the same rows) after revalidating everything it was decided on: the film's IMDb id, that the file is still one of its
 * candidates and not blocked, the edition's state (still due for the same reason, the owner's file unchanged), and that
 * the cap has room. Nothing here deletes a download.
 */
@Service
public class ForcedDownloadService {

    private static final Logger LOG = LoggerFactory.getLogger(ForcedDownloadService.class);

    /** What {@link #add} did. */
    public enum AddOutcome {
        /** The download was added. */
        ADDED,
        /** The film, the file or the edition changed since it was decided on: nothing was added, it is due again. */
        DROPPED,
        /** A scan or an Admin edit holds the library lock: nothing was added, try again later. */
        BUSY
    }

    private final FilmRepository films;
    private final EditionRepository editions;
    private final SubtitleSidecarRepository sidecars;
    private final ForcedResolver resolver;
    private final ForcedNeedService need;
    private final OpenSubtitlesReferenceRepository references;
    private final ForcedDownloadBlockRepository blocks;
    private final ForcedCheckCandidateRepository ledger;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public ForcedDownloadService(FilmRepository films, EditionRepository editions, SubtitleSidecarRepository sidecars,
                                 ForcedResolver resolver, ForcedNeedService need,
                                 OpenSubtitlesReferenceRepository references, ForcedDownloadBlockRepository blocks,
                                 ForcedCheckCandidateRepository ledger, LibraryLock lock,
                                 PlatformTransactionManager transactions) {
        this.films = films;
        this.editions = editions;
        this.sidecars = sidecars;
        this.resolver = resolver;
        this.need = need;
        this.references = references;
        this.blocks = blocks;
        this.ledger = ledger;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * The editions to download for, missing ones first and then replacements, each by film and edition. A film is
     * considered when its need is shown ({@code NEEDED}) and it has single-file results from a search of its current
     * IMDb id (the candidates stored). Per edition see {@link DownloadPlan#kind}; an edition that already has
     * {@value DownloadPlan#MAX_DOWNLOADS} downloads (removed ones included) is left alone, and so is one with nothing
     * left to try.
     *
     * @return the due editions in the order to work on them
     */
    @Transactional(readOnly = true)
    public List<DueDownload> due() {
        Set<Long> neededFilmIds = new HashSet<>();
        for (FilmNeedDto film : need.list().films()) {
            if ("NEEDED".equals(film.need())) {
                neededFilmIds.add(film.filmId());
            }
        }
        Map<Long, OsBestRef> best = new HashMap<>();
        for (OsBestRef ref : films.findOpenSubtitlesBest()) {
            best.put(ref.id(), ref);
        }
        neededFilmIds.retainAll(best.keySet());
        if (neededFilmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(neededFilmIds);
        Map<Long, Resolution> resolved = resolver.resolve(fetched);
        List<Long> editionIds = fetched.stream().map(Edition::getId).toList();
        List<Long> partIds = fetched.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList();
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdIn(editionIds);
        List<ForcedCheckCandidate> allLedger = ledger.findByEditionIdIn(editionIds);
        List<SubtitleSidecar> allDownloads = sidecars.findByMediaFileIdIn(partIds).stream()
            .filter(SubtitleSidecar::isDownload).toList();
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm = new HashMap<>();
        references.findByFilmIdIn(neededFilmIds).forEach(r -> storedByFilm
            .computeIfAbsent(r.getFilmId(), k -> new HashMap<>())
            .computeIfAbsent(r.getImdbId(), k -> new HashMap<>()).put(r.getOsFileId(), r));
        List<DueDownload> due = new ArrayList<>();
        for (Edition edition : fetched) {
            Resolution resolution = resolved.get(edition.getId());
            OsBestRef ref = best.get(edition.getFilm().getId());
            if (ref.osCandidates() == null) {
                continue;
            }
            DueDownload item = dueFor(edition, resolution, ref, allBlocks, allLedger, allDownloads,
                storedByFilm.getOrDefault(ref.id(), Map.of()).getOrDefault(ref.imdbId(), Map.of()));
            if (item != null) {
                due.add(item);
            }
        }
        due.sort(Comparator.comparing(DueDownload::kind).thenComparing(DueDownload::filmId)
            .thenComparing(DueDownload::editionId));
        return due;
    }

    private DueDownload dueFor(Edition edition, Resolution resolution, OsBestRef ref,
                               List<ForcedDownloadBlock> allBlocks, List<ForcedCheckCandidate> allLedger,
                               List<SubtitleSidecar> allDownloads, Map<Long, OpenSubtitlesReference> stored) {
        Optional<Kind> kind = kind(resolution);
        if (kind.isEmpty()) {
            return null;
        }
        if (resolution.decision().step() == ForcedChoice.Step.FAILED_FILE_DROPPED) {
            LOG.info("Edition {}: the owner's file failed its checks and looks like full subtitles ({} lines): "
                + "nothing is shown until a replacement is proven", edition.getId(),
                resolution.filePick().slots().stream().mapToInt(x -> x.sidecar().getCueCount()).sum());
        }
        List<OpenSubtitlesResult> ranked = OpenSubtitlesRanking.rankFor(
            OpenSubtitlesResult.listFromJson(ref.osCandidates()).stream().filter(OpenSubtitlesResult::isSingleFile)
                .toList(), edition.getName());
        Set<Long> skipped = skippedFileIds(edition, ref.imdbId(), allBlocks, allDownloads);
        int remaining = DownloadPlan.remaining(skipped);
        if (ranked.isEmpty() || remaining == 0) {
            return null;
        }
        Set<Long> compared = new HashSet<>();
        allLedger.stream().filter(l -> l.getEditionId().equals(edition.getId()) && l.getImdbId().equals(ref.imdbId()))
            .forEach(l -> compared.add(l.getOsFileId()));
        OpenSubtitlesReference promotion = null;
        String fileVersion = null;
        if (kind.get() == Kind.REPLACEMENT) {
            fileVersion = resolution.fileVersion();
            // A file that has failed has a version, so its timeline is known and it can be served.
            ServedSubtitle served = ServedSubtitles.of(edition, resolution.filePick());
            promotion = DownloadPlan.promotion(ranked, resolution.fileReferenceFileId(), stored, compared, skipped,
                served.cues()).orElse(null);
        }
        List<OpenSubtitlesResult> toFetch = DownloadPlan.toFetch(ranked, skipped, compared, stored);
        if (promotion == null && toFetch.isEmpty()) {
            return null;
        }
        return new DueDownload(kind.get(), ref.id(), edition.getId(), ref.imdbId(), edition.getName(), promotion,
            toFetch, remaining, fileVersion);
    }

    /** Why (and whether) an edition is due, from its resolution (the need is already shown). */
    private static Optional<Kind> kind(Resolution resolution) {
        FileSide file = resolution.fileAssessment() == null ? null : new FileSide(resolution.fileAssessment(),
            resolution.filePick().slots().stream().mapToInt(s -> s.sidecar().getCueCount()).sum());
        return DownloadPlan.kind(true, resolution.fileConflict(), file, resolution.decision().hasLiveDownload(),
            resolution.embedded());
    }

    /** The OpenSubtitles file ids already used up for an edition and IMDb id: its downloads and the ones removed. */
    private static Set<Long> skippedFileIds(Edition edition, String imdbId, List<ForcedDownloadBlock> allBlocks,
                                            List<SubtitleSidecar> allDownloads) {
        Set<Long> skipped = new HashSet<>();
        allBlocks.stream().filter(b -> b.getEditionId().equals(edition.getId()) && b.getImdbId().equals(imdbId))
            .forEach(b -> skipped.add(b.getOsFileId()));
        allDownloads.stream().filter(d -> Downloads.madeFor(edition, d) && imdbId.equals(d.getImdbId()))
            .forEach(d -> skipped.add(d.getOpenSubtitlesFileId()));
        return skipped;
    }

    /**
     * Makes a stored OpenSubtitles reference the edition's download. Under the library lock, in one transaction, and
     * only while everything it was decided on still holds (the film's IMDb id, the file still one of its single-file
     * candidates and not blocked, nothing already made of it, room under the cap, the edition still due for the same
     * reason with the owner's file unchanged). The file goes on the ledger of files the edition has been compared with,
     * so the second-opinion comparison of the download counts it.
     *
     * @param due the edition and why
     * @param reference the stored reference to use (usable, under {@value SubtitleComparison#FULL_LINES} lines)
     * @param now the time to stamp
     * @return what happened
     */
    public AddOutcome add(DueDownload due, OpenSubtitlesReference reference, Instant now) {
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            return AddOutcome.BUSY;
        }
        try {
            return tx.execute(status -> addLocked(due, reference, now));
        } catch (DataIntegrityViolationException e) {
            // Another writer made the same download first.
            return AddOutcome.DROPPED;
        } finally {
            lock.release();
        }
    }

    private AddOutcome addLocked(DueDownload due, OpenSubtitlesReference reference, Instant now) {
        Film film = films.findById(due.filmId()).orElse(null);
        if (film == null || !due.imdbId().equals(film.getImdbId()) || !due.imdbId().equals(reference.getImdbId())
            || !DownloadPlan.promotable(reference) || film.getOsCandidates() == null) {
            return AddOutcome.DROPPED;
        }
        // The owner may have said "not needed" since the run decided: that choice takes the same lock, so it shows.
        if (!need.isNeeded(due.filmId())) {
            return AddOutcome.DROPPED;
        }
        boolean candidate = OpenSubtitlesResult.listFromJson(film.getOsCandidates()).stream()
            .anyMatch(c -> c.isSingleFile() && c.fileId() == reference.getOsFileId());
        Edition edition = editions.findWithFilesByFilmIdIn(List.of(due.filmId())).stream()
            .filter(e -> e.getId().equals(due.editionId())).findFirst().orElse(null);
        if (!candidate || edition == null) {
            return AddOutcome.DROPPED;
        }
        List<ForcedDownloadBlock> editionBlocks = blocks.findByEditionIdIn(List.of(edition.getId()));
        List<SubtitleSidecar> downloads = sidecars
            .findByMediaFileIdIn(edition.originalParts().stream().map(MediaFile::getId).toList()).stream()
            .filter(SubtitleSidecar::isDownload).toList();
        Set<Long> skipped = skippedFileIds(edition, due.imdbId(), editionBlocks, downloads);
        if (skipped.contains(reference.getOsFileId()) || DownloadPlan.remaining(skipped) == 0) {
            return AddOutcome.DROPPED;
        }
        Resolution resolution = resolver.resolve(List.of(edition)).get(edition.getId());
        Optional<Kind> kind = kind(resolution);
        if (kind.isEmpty() || kind.get() != due.kind()
            || due.kind() == Kind.REPLACEMENT && !Objects.equals(due.fileVersion(), resolution.fileVersion())) {
            return AddOutcome.DROPPED;
        }
        sidecars.save(download(edition, reference, now));
        if (!ledger.existsByEditionIdAndImdbIdAndOsFileId(edition.getId(), due.imdbId(), reference.getOsFileId())) {
            ForcedCheckCandidate row = new ForcedCheckCandidate();
            row.setEditionId(edition.getId());
            row.setImdbId(due.imdbId());
            row.setOsFileId(reference.getOsFileId());
            row.setTriedAt(now);
            ledger.save(row);
        }
        return AddOutcome.ADDED;
    }

    /** The download row: the reference's cues, stored against part 1 as a whole-edition file. */
    private static SubtitleSidecar download(Edition edition, OpenSubtitlesReference reference, Instant now) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setOrigin(SubtitleSidecar.Origin.OPENSUBTITLES);
        row.setPath("opensubtitles:" + edition.getId() + ":" + reference.getOsFileId());
        row.setCuesText(reference.getCuesText());
        row.setCueCount(reference.getCueCount());
        row.setLastEndMs(SrtParser.cues(reference.getCuesText()).stream().mapToLong(SubtitleCue::endMs).max()
            .orElse(0));
        row.setSizeBytes(reference.getCuesText().length());
        row.setModifiedAt(now);
        row.setPresence(Presence.PRESENT);
        row.setMediaFileId(edition.originalParts().get(0).getId());
        row.setWholeEdition(true);
        row.setOwnerAttached(false);
        row.setParseReport("Downloaded from OpenSubtitles");
        row.setOpenSubtitlesFileId(reference.getOsFileId());
        row.setRelease(reference.getRelease());
        row.setImdbId(reference.getImdbId());
        row.setDownloadedAt(now);
        row.setUpdatedAt(now);
        return row;
    }
}
