package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.DownloadPlan.Kind;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
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
 *
 * <p>A <em>full</em> English subtitle ({@link Kind#FULL}) is downloaded the same way, from its own due source: films
 * whose full need ({@link FullNeedService}) is {@code NEEDED} (but not those the owner marked {@code NONE_EXIST})
 * and that have full candidates ({@code film.os_full_candidates}). Its blocks, ledger and downloads are read with
 * {@link SubtitleKind#FULL}, so the two kinds never count against each other's cap.
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

    /** What {@link #addChosen} and {@link #refusal} said about a file the owner picked for an edition. */
    public enum ChosenOutcome {
        /** The download was added and pinned. */
        ADDED,
        /** A scan or an Admin edit holds the library lock: nothing was added, try again later. */
        BUSY,
        /** The edition is gone, or its film's IMDb id is no longer the one that was searched. */
        CHANGED,
        /** The owner marked the film as not needing subtitles of this kind. */
        NOT_WANTED,
        /** The file is not a usable download of this kind (unreadable, full for forced, too short for full). */
        UNUSABLE,
        /** The owner removed this file from the edition earlier: it is never downloaded for it again. */
        BLOCKED,
        /** The edition already has a download made from this file. */
        ALREADY_DOWNLOADED,
        /** The edition already has {@value DownloadPlan#MAX_DOWNLOADS} files used (downloads and removed ones). */
        CAP_REACHED,
        /** The edition has no original file (only premade renditions): a download has nothing to be attached to. */
        NO_FILE
    }

    private final FilmRepository films;
    private final EditionRepository editions;
    private final SubtitleSidecarRepository sidecars;
    private final ForcedResolver resolver;
    private final ForcedNeedService need;
    private final FullNeedService fullNeed;
    private final OpenSubtitlesReferenceRepository references;
    private final ForcedDownloadBlockRepository blocks;
    private final ForcedCheckCandidateRepository ledger;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public ForcedDownloadService(FilmRepository films, EditionRepository editions, SubtitleSidecarRepository sidecars,
                                 ForcedResolver resolver, ForcedNeedService need, FullNeedService fullNeed,
                                 OpenSubtitlesReferenceRepository references, ForcedDownloadBlockRepository blocks,
                                 ForcedCheckCandidateRepository ledger, LibraryLock lock,
                                 PlatformTransactionManager transactions) {
        this.films = films;
        this.editions = editions;
        this.sidecars = sidecars;
        this.resolver = resolver;
        this.need = need;
        this.fullNeed = fullNeed;
        this.references = references;
        this.blocks = blocks;
        this.ledger = ledger;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * The editions to download for, in the order to work on them: promotions first (they cost nothing, whatever the
     * kind), then <strong>full</strong> English subtitles (the film cannot be watched without them), then forced
     * replacements (the owner's file failed every check, so the film shows nothing now), then forced missing ones, each
     * group by film and edition. A forced edition is considered when its need is shown ({@code NEEDED}) and its film
     * has single-file results from a search of its current IMDb id (the candidates stored); per edition see
     * {@link DownloadPlan#kind}. A full edition is considered when its film's full need is {@code NEEDED} (and not
     * {@code NONE_EXIST}) and it has full candidates from a search of its current IMDb id; it is due when it has no
     * present owner's full file and no full download ({@link DownloadPlan#fullDue}). An edition that already has
     * {@value DownloadPlan#MAX_DOWNLOADS} downloads of the kind (removed ones included) is left alone,
     * and so is one with nothing left to try.
     *
     * @return the due editions in the order to work on them
     */
    @Transactional(readOnly = true)
    public List<DueDownload> due() {
        List<DueDownload> due = new ArrayList<>(forcedDue());
        due.addAll(fullDue());
        // Free promotions first, then full subtitles, then replacements (the owner's file failed, so nothing is shown
        // now), then missing: the order of Kind.
        due.sort(Comparator.comparing((DueDownload d) -> d.promotion() == null)
            .thenComparing(ForcedDownloadService::rank)
            .thenComparing(DueDownload::filmId).thenComparing(DueDownload::editionId));
        return due;
    }

    /** Full subtitles first, then replacements, then missing ones. */
    private static int rank(DueDownload due) {
        return switch (due.kind()) {
            case FULL -> 0;
            case REPLACEMENT -> 1;
            case MISSING -> 2;
        };
    }

    private List<DueDownload> forcedDue() {
        Set<Long> neededFilmIds = need.neededFilmIds();
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
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdInAndKind(editionIds, SubtitleKind.FORCED);
        List<ForcedCheckCandidate> allLedger = ledger.findByEditionIdInAndKind(editionIds, SubtitleKind.FORCED);
        List<SubtitleSidecar> allDownloads = sidecars.findByMediaFileIdInAndKind(partIds, SubtitleKind.FORCED)
            .stream()
            .filter(SubtitleSidecar::isDownload).toList();
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm = storedByFilm(neededFilmIds);
        List<DueDownload> due = new ArrayList<>();
        for (Edition edition : fetched) {
            Resolution resolution = resolved.get(edition.getId());
            OsBestRef ref = best.get(edition.getFilm().getId());
            if (ref.osCandidates() == null) {
                continue;
            }
            if (resolution.decision().step() == ForcedChoice.Step.FAILED_FILE_DROPPED && kind(resolution).isPresent()) {
                LOG.info("Edition {}: the owner's file failed its checks and looks like full subtitles ({} lines): "
                    + "nothing is shown until a replacement is proven", edition.getId(),
                    resolution.filePick().slots().stream().mapToInt(x -> x.sidecar().getCueCount()).sum());
            }
            DueDownload item = dueFor(edition, resolution, ref, allBlocks, allLedger, allDownloads,
                storedByFilm.getOrDefault(ref.id(), Map.of()).getOrDefault(ref.imdbId(), Map.of()));
            if (item != null) {
                due.add(item);
            }
        }
        return due;
    }

    private Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm(Set<Long> filmIds) {
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm = new HashMap<>();
        references.findByFilmIdIn(filmIds).forEach(r -> storedByFilm
            .computeIfAbsent(r.getFilmId(), k -> new HashMap<>())
            .computeIfAbsent(r.getImdbId(), k -> new HashMap<>()).put(r.getOsFileId(), r));
        return storedByFilm;
    }

    /**
     * The editions due a full English subtitle: the film's need is {@code NEEDED} (the owner's {@code NONE_EXIST} films
     * are left out) and it has full candidates.
     */
    private List<DueDownload> fullDue() {
        Set<Long> neededFilmIds = fullNeed.searchFilmIds();
        Map<Long, OsFullRef> candidates = new HashMap<>();
        for (OsFullRef ref : films.findOpenSubtitlesFull()) {
            candidates.put(ref.id(), ref);
        }
        neededFilmIds.retainAll(candidates.keySet());
        if (neededFilmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(neededFilmIds);
        List<Long> editionIds = fetched.stream().map(Edition::getId).toList();
        List<Long> partIds = fetched.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList();
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdInAndKind(editionIds, SubtitleKind.FULL);
        List<ForcedCheckCandidate> allLedger = ledger.findByEditionIdInAndKind(editionIds, SubtitleKind.FULL);
        List<SubtitleSidecar> allFull = sidecars.findByMediaFileIdInAndKind(partIds, SubtitleKind.FULL);
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm = storedByFilm(neededFilmIds);
        List<DueDownload> due = new ArrayList<>();
        for (Edition edition : fetched) {
            OsFullRef ref = candidates.get(edition.getFilm().getId());
            DueDownload item = fullDueFor(edition, ref, allBlocks, allLedger, allFull,
                storedByFilm.getOrDefault(ref.id(), Map.of()).getOrDefault(ref.imdbId(), Map.of()));
            if (item != null) {
                due.add(item);
            }
        }
        return due;
    }

    /**
     * The scheduler's decision for one edition's full download, pure over bulk-loaded facts: null when nothing is to
     * be downloaded for it. {@code PendingDownloads} calls this too, so what Admin calls "being checked" is exactly
     * what the scheduler would act on.
     */
    static DueDownload fullDueFor(Edition edition, OsFullRef ref, List<ForcedDownloadBlock> allBlocks,
                                   List<ForcedCheckCandidate> allLedger, List<SubtitleSidecar> allFull,
                                   Map<Long, OpenSubtitlesReference> stored) {
        if (!DownloadPlan.fullDue(hasOwnerFile(edition, allFull), hasDownload(edition, allFull))) {
            return null;
        }
        List<OpenSubtitlesResult> ranked = OpenSubtitlesRanking.rankForFull(
            OpenSubtitlesResult.listFromJson(ref.osFullCandidates()).stream()
                .filter(OpenSubtitlesResult::isSingleFile).toList(), edition.getName());
        Set<Long> skipped = skippedFileIds(edition, ref.imdbId(), SubtitleKind.FULL, allBlocks,
            allFull.stream().filter(SubtitleSidecar::isDownload).toList());
        int remaining = DownloadPlan.remaining(skipped);
        if (ranked.isEmpty() || remaining == 0) {
            return null;
        }
        Set<Long> compared = new HashSet<>();
        allLedger.stream().filter(l -> l.getEditionId().equals(edition.getId()) && l.getImdbId().equals(ref.imdbId()))
            .forEach(l -> compared.add(l.getOsFileId()));
        List<OpenSubtitlesResult> toFetch = DownloadPlan.toFetch(ranked, skipped, compared, stored, SubtitleKind.FULL);
        if (toFetch.isEmpty()) {
            return null;
        }
        return new DueDownload(Kind.FULL, ref.id(), edition.getId(), ref.imdbId(), edition.getName(), null, toFetch,
            remaining, null);
    }

    /** True when a present owner's full file with lines is attached to one of the edition's parts. */
    static boolean hasOwnerFile(Edition edition, List<SubtitleSidecar> allFull) {
        List<Long> partIds = partIdsOf(edition);
        return allFull.stream().anyMatch(s -> !s.isDownload() && s.getPresence() == Presence.PRESENT
            && s.getCueCount() > 0 && partIds.contains(s.getMediaFileId()));
    }

    /**
     * True when the edition has a live full download (made for it, present, with lines), as the forced path's
     * {@code hasLiveDownload}. A download that is not present does not suppress a retry; removed files stay counted
     * by the cap through their blocks.
     */
    static boolean hasDownload(Edition edition, List<SubtitleSidecar> allFull) {
        return allFull.stream().anyMatch(s -> s.isDownload() && Downloads.madeFor(edition, s)
            && s.getPresence() == Presence.PRESENT && s.getCueCount() > 0);
    }

    private static List<Long> partIdsOf(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }

    /**
     * The scheduler's decision for one edition's forced download, pure over bulk-loaded facts: null when nothing is to
     * be downloaded for it (not due, no single-file candidate, none left after blocks, the ledger and spent
     * references, or the cap reached). {@code PendingDownloads} calls this too.
     */
    static DueDownload dueFor(Edition edition, Resolution resolution, OsBestRef ref,
                               List<ForcedDownloadBlock> allBlocks, List<ForcedCheckCandidate> allLedger,
                               List<SubtitleSidecar> allDownloads, Map<Long, OpenSubtitlesReference> stored) {
        Optional<Kind> kind = kind(resolution);
        if (kind.isEmpty()) {
            return null;
        }
        List<OpenSubtitlesResult> ranked = OpenSubtitlesRanking.rankFor(
            OpenSubtitlesResult.listFromJson(ref.osCandidates()).stream().filter(OpenSubtitlesResult::isSingleFile)
                .toList(), edition.getName());
        Set<Long> skipped = skippedFileIds(edition, ref.imdbId(), SubtitleKind.FORCED, allBlocks, allDownloads);
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
    static Optional<Kind> kind(Resolution resolution) {
        FileSide file = resolution.fileAssessment() == null ? null : new FileSide(resolution.fileAssessment(),
            resolution.filePick().slots().stream().mapToInt(s -> s.sidecar().getCueCount()).sum());
        return DownloadPlan.kind(true, resolution.fileConflict(), file, resolution.decision().hasLiveDownload(),
            resolution.embedded());
    }

    /**
     * The OpenSubtitles file ids already used up for an edition, IMDb id and kind: its downloads and the ones removed.
     * A download or block of the other kind never counts.
     */
    static Set<Long> skippedFileIds(Edition edition, String imdbId, SubtitleKind kind,
                                            List<ForcedDownloadBlock> allBlocks, List<SubtitleSidecar> allDownloads) {
        Set<Long> skipped = new HashSet<>();
        allBlocks.stream().filter(b -> b.getKind() == kind && b.getEditionId().equals(edition.getId())
            && b.getImdbId().equals(imdbId))
            .forEach(b -> skipped.add(b.getOsFileId()));
        allDownloads.stream().filter(d -> d.getKind() == kind && Downloads.madeFor(edition, d)
            && imdbId.equals(d.getImdbId()))
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
     * @param reference the stored reference to use (usable; under {@value SubtitleComparison#FULL_LINES} lines for
     *     a forced download, at least that many for a full one)
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
        boolean full = due.kind() == Kind.FULL;
        SubtitleKind kind = full ? SubtitleKind.FULL : SubtitleKind.FORCED;
        Film film = films.findById(due.filmId()).orElse(null);
        if (film == null || !due.imdbId().equals(film.getImdbId()) || !due.imdbId().equals(reference.getImdbId())
            || !DownloadPlan.promotable(reference, kind)) {
            return AddOutcome.DROPPED;
        }
        String candidatesJson = full ? film.getOsFullCandidates() : film.getOsCandidates();
        if (candidatesJson == null || full && !due.imdbId().equals(film.getOsFullSearchedImdbId())) {
            return AddOutcome.DROPPED;
        }
        // The owner may have said "not needed" since the run decided: that choice takes the same lock, so it shows.
        if (!(full ? fullNeed.isNeeded(due.filmId()) : need.isNeeded(due.filmId()))) {
            return AddOutcome.DROPPED;
        }
        OpenSubtitlesResult candidate = OpenSubtitlesResult.listFromJson(candidatesJson).stream()
            .filter(c -> c.isSingleFile() && c.fileId() == reference.getOsFileId()).findFirst().orElse(null);
        Edition edition = editions.findWithFilesByFilmIdIn(List.of(due.filmId())).stream()
            .filter(e -> e.getId().equals(due.editionId())).findFirst().orElse(null);
        if (candidate == null || edition == null) {
            return AddOutcome.DROPPED;
        }
        List<ForcedDownloadBlock> editionBlocks = blocks.findByEditionIdInAndKind(List.of(edition.getId()), kind);
        List<SubtitleSidecar> ofKind = sidecars
            .findByMediaFileIdInAndKind(edition.originalParts().stream().map(MediaFile::getId).toList(), kind);
        List<SubtitleSidecar> downloads = ofKind.stream().filter(SubtitleSidecar::isDownload).toList();
        Set<Long> skipped = skippedFileIds(edition, due.imdbId(), kind, editionBlocks, downloads);
        if (skipped.contains(reference.getOsFileId()) || DownloadPlan.remaining(skipped) == 0) {
            return AddOutcome.DROPPED;
        }
        if (full ? !stillDueFull(edition, ofKind) : !stillDueForced(edition, due)) {
            return AddOutcome.DROPPED;
        }
        sidecars.save(download(edition, reference, now, kind, candidate.fps()));
        if (!ledger.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition.getId(), due.imdbId(),
            reference.getOsFileId(), kind)) {
            ForcedCheckCandidate row = new ForcedCheckCandidate();
            row.setEditionId(edition.getId());
            row.setImdbId(due.imdbId());
            row.setKind(kind);
            row.setOsFileId(reference.getOsFileId());
            row.setTriedAt(now);
            ledger.save(row);
        }
        return AddOutcome.ADDED;
    }

    /** A full download is still due: no owner's full file and no full download appeared meanwhile. */
    private static boolean stillDueFull(Edition edition, List<SubtitleSidecar> fullRows) {
        return DownloadPlan.fullDue(hasOwnerFile(edition, fullRows), hasDownload(edition, fullRows));
    }

    /** A forced download is still due for the same reason with the owner's file unchanged. */
    private boolean stillDueForced(Edition edition, DueDownload due) {
        Resolution resolution = resolver.resolve(List.of(edition)).get(edition.getId());
        Optional<Kind> kind = kind(resolution);
        return kind.isPresent() && kind.get() == due.kind()
            && !(due.kind() == Kind.REPLACEMENT && !Objects.equals(due.fileVersion(), resolution.fileVersion()));
    }

    /**
     * The download row: the reference's cues, stored against part 1 as a whole-edition file. Its stated rate is the
     * reference's when it has one, else the one the film's current search result for the file states (a reference
     * stored before the rate was kept has none; null when neither does).
     */
    private static SubtitleSidecar download(Edition edition, OpenSubtitlesReference reference, Instant now,
                                            SubtitleKind kind, Double candidateFps) {
        SubtitleSidecar row = new SubtitleSidecar();
        row.setKind(kind);
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
        row.setTimedFps(reference.getTimedFps() != null ? reference.getTimedFps() : candidateFps);
        row.setDownloadedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /**
     * Why a file the owner picked in the picker cannot become the edition's download, from what is stored now (no
     * download is made and nothing is locked, so it is cheap to ask before a download is spent).
     *
     * @param filmId the edition's film
     * @param editionId the edition
     * @param kind forced or full
     * @param imdbId the IMDb id the search was made for
     * @param fileId the OpenSubtitles file id
     * @return the reason, or empty when nothing stops it
     */
    @Transactional(readOnly = true)
    public Optional<ChosenOutcome> refusal(Long filmId, Long editionId, SubtitleKind kind, String imdbId,
                                           long fileId) {
        Edition edition = loadEdition(filmId, editionId);
        return edition == null ? Optional.of(ChosenOutcome.CHANGED) : refusalFor(edition, kind, imdbId, fileId);
    }

    /**
     * Whether an edition has an original file for a download to be attached to (an edition with only premade
     * renditions has none).
     *
     * @param filmId the edition's film
     * @param editionId the edition
     * @return true when it has at least one original part
     */
    @Transactional(readOnly = true)
    public boolean hasOriginalFile(Long filmId, Long editionId) {
        Edition edition = loadEdition(filmId, editionId);
        return edition != null && !edition.originalParts().isEmpty();
    }

    /**
     * How many more files the edition may be given of a kind: {@value DownloadPlan#MAX_DOWNLOADS} less the downloads
     * and removed files it has for the IMDb id.
     *
     * @param filmId the edition's film
     * @param editionId the edition
     * @param kind forced or full
     * @param imdbId the film's IMDb id
     * @return the number left, 0 when the edition is gone
     */
    @Transactional(readOnly = true)
    public int downloadsLeft(Long filmId, Long editionId, SubtitleKind kind, String imdbId) {
        Edition edition = loadEdition(filmId, editionId);
        return edition == null ? 0 : DownloadPlan.remaining(skipped(edition, kind, imdbId));
    }

    /**
     * Makes a file the owner picked the edition's download, and pins it: the row carries {@code ownerChosenAt}, so it
     * is proven whatever a comparison says (never judged failed, so the nightly plan finds a live download and adds
     * none) and plays before another download; an earlier pick of the same edition and kind loses its mark. The
     * owner's check ({@code ownerVerifiedAt}) is not set, so the timing fit still applies and no "you checked it" is
     * claimed.
     * Runs under the library lock, in one transaction, after revalidating everything that can have changed since the
     * picker looked: the film's IMDb id, that the edition still has an original file (a premade rendition cannot be
     * attached to), that the owner has not marked the film as not needing this kind, that the
     * file was not removed or already downloaded, and that the cap has room. The film's stored candidates and its
     * need are not consulted: the owner chose this file.
     *
     * @param filmId the edition's film
     * @param editionId the edition
     * @param kind forced or full
     * @param reference the stored reference of the picked file
     * @param now the time to stamp
     * @return what happened
     */
    public ChosenOutcome addChosen(Long filmId, Long editionId, SubtitleKind kind, OpenSubtitlesReference reference,
                                   Instant now) {
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            return ChosenOutcome.BUSY;
        }
        try {
            return tx.execute(status -> addChosenLocked(filmId, editionId, kind, reference, now));
        } catch (DataIntegrityViolationException e) {
            // Another writer made the same download first.
            return ChosenOutcome.ALREADY_DOWNLOADED;
        } finally {
            lock.release();
        }
    }

    private ChosenOutcome addChosenLocked(Long filmId, Long editionId, SubtitleKind kind,
                                          OpenSubtitlesReference reference, Instant now) {
        Edition edition = loadEdition(filmId, editionId);
        if (edition == null) {
            return ChosenOutcome.CHANGED;
        }
        Optional<ChosenOutcome> refusal = refusalFor(edition, kind, reference.getImdbId(), reference.getOsFileId());
        if (refusal.isPresent()) {
            return refusal.get();
        }
        if (!DownloadPlan.promotable(reference, kind)) {
            return ChosenOutcome.UNUSABLE;
        }
        // The latest pick is the one that plays and is pinned: an earlier pick for this edition and kind goes back to
        // being an ordinary download (a download made for another edition that shares a part file is left alone).
        for (SubtitleSidecar earlier : downloadsOf(edition, kind)) {
            if (earlier.isOwnerChosen() && Downloads.madeFor(edition, earlier)) {
                earlier.setOwnerChosenAt(null);
                sidecars.save(earlier);
            }
        }
        SubtitleSidecar row = download(edition, reference, now, kind, null);
        row.setOwnerChosenAt(now);
        sidecars.save(row);
        if (!ledger.existsByEditionIdAndImdbIdAndOsFileIdAndKind(edition.getId(), reference.getImdbId(),
            reference.getOsFileId(), kind)) {
            ForcedCheckCandidate entry = new ForcedCheckCandidate();
            entry.setEditionId(edition.getId());
            entry.setImdbId(reference.getImdbId());
            entry.setKind(kind);
            entry.setOsFileId(reference.getOsFileId());
            entry.setTriedAt(now);
            ledger.save(entry);
        }
        return ChosenOutcome.ADDED;
    }

    private Edition loadEdition(Long filmId, Long editionId) {
        return editions.findWithFilesByFilmIdIn(List.of(filmId)).stream()
            .filter(e -> e.getId().equals(editionId)).findFirst().orElse(null);
    }

    private Set<Long> skipped(Edition edition, SubtitleKind kind, String imdbId) {
        return skippedFileIds(edition, imdbId, kind, blocksOf(edition, kind), downloadsOf(edition, kind));
    }

    private List<ForcedDownloadBlock> blocksOf(Edition edition, SubtitleKind kind) {
        return blocks.findByEditionIdInAndKind(List.of(edition.getId()), kind);
    }

    private List<SubtitleSidecar> downloadsOf(Edition edition, SubtitleKind kind) {
        return sidecars.findByMediaFileIdInAndKind(partIdsOf(edition), kind).stream()
            .filter(SubtitleSidecar::isDownload).toList();
    }

    private Optional<ChosenOutcome> refusalFor(Edition edition, SubtitleKind kind, String imdbId, long fileId) {
        if (edition.originalParts().isEmpty()) {
            return Optional.of(ChosenOutcome.NO_FILE);
        }
        if (imdbId == null || !imdbId.equals(edition.getFilm().getImdbId())) {
            return Optional.of(ChosenOutcome.CHANGED);
        }
        if (Downloads.isNotNeeded(edition, kind)) {
            return Optional.of(ChosenOutcome.NOT_WANTED);
        }
        List<ForcedDownloadBlock> editionBlocks = blocksOf(edition, kind);
        if (editionBlocks.stream().anyMatch(b -> b.getKind() == kind && b.getImdbId().equals(imdbId)
            && b.getOsFileId() == fileId)) {
            return Optional.of(ChosenOutcome.BLOCKED);
        }
        Set<Long> skipped = skippedFileIds(edition, imdbId, kind, editionBlocks, downloadsOf(edition, kind));
        if (skipped.contains(fileId)) {
            return Optional.of(ChosenOutcome.ALREADY_DOWNLOADED);
        }
        return DownloadPlan.remaining(skipped) == 0 ? Optional.of(ChosenOutcome.CAP_REACHED) : Optional.empty();
    }
}
