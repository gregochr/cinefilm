package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository.ReferenceFacts;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database side of checking the timing of the forced subtitle files playback serves against the full English
 * subtitle track inside each film's own media file: which films are due, which files must be read, what to fit, and
 * saving a result. Kept apart from {@link SidecarTimingRefresher} (which runs ffprobe) so each can be tested alone.
 *
 * <p>Only a subtitle that playback could serve is checked: the owner's files as {@link SidecarPrecedence} picks them
 * and the edition's live downloads ({@link Downloads}). A result is saved only while the file
 * is still the one that was fitted (its {@link TimingKeys#identity}) and the reference is still the one it was fitted
 * to ({@link TimingKeys#referenceKey}); the save takes the {@link LibraryLock} (a scan and Admin's edits rewrite the
 * same rows) and sets the file's offset only when {@link #shouldApply} says so, judged on the row as it is then, not
 * as it was when the check began.
 */
@Service
public class SidecarTimingService {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarTimingService.class);

    /** A fitted offset closer than this to the current one is left alone. */
    static final int APPLY_THRESHOLD_MS = 100;

    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;
    private final MediaFileRepository files;
    private final EmbeddedCueStartsRepository cache;
    private final ForcedSubtitleAlignmentRepository alignments;
    private final OpenSubtitlesReferenceRepository references;
    private final CreditsDetectionRepository credits;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /** A film file whose embedded track must be read (it has no read, or its file changed since). */
    public record ReferenceRead(Long mediaFileId, String path, String fingerprint) {
    }

    /**
     * A sidecar to fit. The cue starts are on the timeline of the span the sidecar covers: its own file for a file
     * timed for one part, the whole edition for one timed for a whole split edition.
     *
     * @param filmId the film
     * @param sidecarId the sidecar
     * @param identity the sidecar as read (see {@link TimingKeys#identity})
     * @param referenceKey what it is fitted to (see {@link TimingKeys#referenceKey})
     * @param servedStarts the sidecar's cue starts as stored, in milliseconds
     * @param referenceStarts the film's English track's cue starts (empty when there is no usable track)
     * @param noReference why there is no usable track, or null
     * @param osReferences the usable stored OpenSubtitles files to fit to when the film has no track of its own (the
     *     fit keeps the best, see {@link DownloadedFit#choose}); empty for a fit to the film's own track
     */
    public record FitJob(Long filmId, Long sidecarId, String identity, String referenceKey, List<Long> servedStarts,
                         List<Long> referenceStarts, String noReference,
                         List<DownloadedFit.Reference> osReferences) {

        /** A fit to the film's own track (or with none). */
        public FitJob(Long filmId, Long sidecarId, String identity, String referenceKey, List<Long> servedStarts,
                      List<Long> referenceStarts, String noReference) {
            this(filmId, sidecarId, identity, referenceKey, servedStarts, referenceStarts, noReference, List.of());
        }
    }

    /**
     * The usable stored OpenSubtitles files among an edition's candidates (their facts, never their cue text) and the
     * IMDb id they were fetched for.
     */
    private record OsRefs(String imdbId, List<ReferenceFacts> usable) {
    }

    /** What {@link #save} did. */
    public enum SaveOutcome {
        /** The result was stored; the offset was left as it was. */
        SAVED,
        /** The result was stored and the sidecar's offset was set to it. */
        APPLIED,
        /** The sidecar or the reference changed while it was fitted: nothing was stored, it is due again. */
        DROPPED,
        /** A scan or an Admin edit holds the library lock: nothing was stored, try again later. */
        BUSY
    }

    /** A sidecar playback serves, with its edition. */
    private record Served(Edition edition, SubtitleSidecar sidecar, List<Long> partIds) {
    }

    private enum State { SKIP, NEEDS_READ, NEEDS_FIT, CURRENT }

    /** Creates the service. */
    public SidecarTimingService(SubtitleSidecarRepository sidecars, EditionRepository editions,
                                MediaFileRepository files, EmbeddedCueStartsRepository cache,
                                ForcedSubtitleAlignmentRepository alignments,
                                OpenSubtitlesReferenceRepository references, CreditsDetectionRepository credits,
                                LibraryLock lock, PlatformTransactionManager transactions) {
        this.sidecars = sidecars;
        this.editions = editions;
        this.files = files;
        this.cache = cache;
        this.alignments = alignments;
        this.references = references;
        this.credits = credits;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * The films with something to do: a served sidecar whose film file has no read of its embedded track as it is now,
     * or that has no current fit. Films with a served sidecar of the owner's that is not verified come first, then
     * films with a sidecar never checked, then by film id. A sidecar that cannot be checked (a part's length unknown,
     * a file missing or with no size or time) does not make its film due.
     *
     * @return film ids in the order to work on them
     */
    @Transactional(readOnly = true)
    public List<Long> dueFilmIds() {
        List<Served> served = served(new HashSet<>(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED)));
        Map<Long, EmbeddedCueStarts> reads = reads(served);
        Map<Long, ForcedSubtitleAlignment> fits = fits(served);
        Map<Long, OsRefs> os = osRefs(served, reads);
        Map<Long, boolean[]> due = new LinkedHashMap<>();
        for (Served s : served) {
            ForcedSubtitleAlignment fit = fits.get(s.sidecar().getId());
            State state = state(s, reads, os, fit);
            if (state == State.NEEDS_READ || state == State.NEEDS_FIT) {
                boolean[] flags = due.computeIfAbsent(s.edition().getFilm().getId(), k -> new boolean[2]);
                flags[0] |= !s.sidecar().isVerifiedFor(s.partIds());
                flags[1] |= fit == null;
            }
        }
        return due.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Long, boolean[]>, Boolean>comparing(e -> !e.getValue()[0])
                .thenComparing(e -> !e.getValue()[1]).thenComparing(Map.Entry::getKey))
            .map(Map.Entry::getKey).toList();
    }

    /**
     * The film files of a film whose embedded track must be read before its sidecars can be fitted.
     *
     * @param filmId the film
     * @return the reads to do, each file once
     */
    @Transactional(readOnly = true)
    public List<ReferenceRead> readsFor(Long filmId) {
        List<Served> served = served(Set.of(filmId));
        Map<Long, EmbeddedCueStarts> reads = reads(served);
        Map<Long, ReferenceRead> needed = new LinkedHashMap<>();
        for (Served s : served) {
            if (state(s, reads, Map.of(), null) == State.NEEDS_READ) {
                for (MediaFile file : TimingKeys.referenceFiles(s.edition(), s.sidecar())) {
                    if (!isRead(file, reads)) {
                        needed.putIfAbsent(file.getId(),
                            new ReferenceRead(file.getId(), file.getPath(), TimingKeys.fingerprint(file)));
                    }
                }
            }
        }
        return List.copyOf(needed.values());
    }

    /**
     * Stores what was read from a film file, but only while the file is still the one that was read: it exists, is
     * present and has the same fingerprint (a rescan that found it changed makes the read stale and drops it). The
     * check and the write are one step under the {@link LibraryLock} (a scan rewrites the file rows), taken only for
     * this short write, never across the read.
     *
     * @param read the read that was asked for
     * @param reading what ffprobe found
     * @param now the time to stamp
     * @return {@code SAVED}; {@code DROPPED} when the file changed or went meanwhile; {@code BUSY} when a scan or an
     *     edit holds the lock (the read is lost and the file is read again later)
     */
    public SaveOutcome saveReference(ReferenceRead read, EmbeddedCueReader.Reading reading, Instant now) {
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            return SaveOutcome.BUSY;
        }
        try {
            return tx.execute(status -> saveReferenceLocked(read, reading, now));
        } finally {
            lock.release();
        }
    }

    private SaveOutcome saveReferenceLocked(ReferenceRead read, EmbeddedCueReader.Reading reading, Instant now) {
        MediaFile file = files.findById(read.mediaFileId()).orElse(null);
        if (file == null || file.getPresence() != Presence.PRESENT
            || !read.fingerprint().equals(TimingKeys.fingerprint(file))) {
            return SaveOutcome.DROPPED;
        }
        EmbeddedCueStarts row = cache.findById(file.getId()).orElseGet(EmbeddedCueStarts::new);
        row.setMediaFileId(file.getId());
        row.setFingerprint(read.fingerprint());
        row.setStatus(reading.status());
        row.setStreamIndex(reading.streamIndex());
        row.setCodec(reading.codec());
        row.setSubtitleStreamCount(reading.subtitleStreamCount());
        row.setStartsMs(reading.startsMs());
        row.setReason(reading.reason());
        row.setReadAt(now);
        cache.save(row);
        return SaveOutcome.SAVED;
    }

    /**
     * The sidecars of a film to fit now: served, with a read of every film file they are fitted to as it is now, and
     * without a current fit.
     *
     * @param filmId the film
     * @return the jobs
     */
    @Transactional(readOnly = true)
    public List<FitJob> fitJobs(Long filmId) {
        List<Served> served = served(Set.of(filmId));
        Map<Long, EmbeddedCueStarts> reads = reads(served);
        Map<Long, ForcedSubtitleAlignment> fits = fits(served);
        Map<Long, OsRefs> os = osRefs(served, reads);
        List<FitJob> jobs = new ArrayList<>();
        for (Served s : served) {
            if (state(s, reads, os, fits.get(s.sidecar().getId())) == State.NEEDS_FIT) {
                jobs.add(job(filmId, s, reads, os));
            }
        }
        return jobs;
    }

    private FitJob job(Long filmId, Served s, Map<Long, EmbeddedCueStarts> reads, Map<Long, OsRefs> os) {
        SubtitleSidecar sidecar = s.sidecar();
        List<Long> starts = SrtParser.cues(sidecar.getCuesText()).stream().map(SubtitleCue::startMs).toList();
        List<ReferenceFacts> downloaded = downloadedFor(s, os);
        if (!downloaded.isEmpty()) {
            // The film has no English track of its own: fit to the stored OpenSubtitles files instead. Only here is
            // their cue text read.
            Map<Long, OpenSubtitlesReference> texts = new HashMap<>();
            references.findAllById(downloaded.stream().map(ReferenceFacts::id).toList())
                .forEach(r -> texts.put(r.getOsFileId(), r));
            List<DownloadedFit.Reference> fitTo = downloaded.stream().map(f -> new DownloadedFit.Reference(
                f.osFileId(), f.release(), f.cueCount(), SrtParser.cues(texts.get(f.osFileId()).getCuesText()).stream()
                .map(SubtitleCue::startMs).toList())).toList();
            return new FitJob(filmId, sidecar.getId(), TimingKeys.identity(sidecar), referenceKey(s, reads, os),
                List.copyOf(starts), List.of(), null, fitTo);
        }
        List<MediaFile> refFiles = TimingKeys.referenceFiles(s.edition(), sidecar);
        EditionTimeline timeline = sidecar.isWholeEdition() ? TimingKeys.timelineOrNull(s.edition()) : null;
        List<Long> reference = new ArrayList<>();
        String noReference = null;
        for (int i = 0; i < refFiles.size() && noReference == null; i++) {
            EmbeddedCueStarts read = reads.get(refFiles.get(i).getId());
            if (read.getStatus() == EmbeddedCueStarts.Status.NO_REFERENCE) {
                noReference = (sidecar.isWholeEdition() ? "Part " + (i + 1) + ": " : "") + read.getReason();
            } else {
                long shift = timeline == null ? 0 : timeline.partStartMs(i);
                read.getStartsMs().forEach(start -> reference.add(start + shift));
            }
        }
        return new FitJob(filmId, sidecar.getId(), TimingKeys.identity(sidecar),
            referenceKey(s, reads, os), List.copyOf(starts),
            noReference == null ? List.copyOf(reference) : List.of(), noReference);
    }

    /**
     * Saves a fit as the sidecar's one current row, and sets the sidecar's offset to it when {@link #shouldApply} says
     * so. Takes the library lock for the write (a scan or an Admin edit rewrites the same rows) and revalidates inside
     * it: the sidecar must still be served, still the file that was fitted and still fitted to the same reference.
     * Anything else is dropped and the sidecar is due again.
     *
     * @param job what was fitted
     * @param result the fit
     * @param now the time to stamp
     * @return what was done
     */
    public SaveOutcome save(FitJob job, TimingFit.Result result, Instant now) {
        return save(job, result, null, now);
    }

    /**
     * Saves a fit as {@link #save(FitJob, TimingFit.Result, Instant)} does, for a fit against a stored OpenSubtitles
     * file: the row records the file, and the offset is never set.
     *
     * @param job what was fitted
     * @param result the fit
     * @param osFileId the OpenSubtitles file fitted to, or null for a fit to the film's own track
     * @param now the time to stamp
     * @return what was done
     */
    public SaveOutcome save(FitJob job, TimingFit.Result result, Long osFileId, Instant now) {
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            return SaveOutcome.BUSY;
        }
        try {
            return tx.execute(status -> saveLocked(job, result, osFileId, now));
        } finally {
            lock.release();
        }
    }

    private SaveOutcome saveLocked(FitJob job, TimingFit.Result result, Long osFileId, Instant now) {
        Served current = served(Set.of(job.filmId())).stream()
            .filter(s -> s.sidecar().getId().equals(job.sidecarId())).findFirst().orElse(null);
        if (current == null) {
            return SaveOutcome.DROPPED;
        }
        SubtitleSidecar sidecar = current.sidecar();
        Map<Long, EmbeddedCueStarts> currentReads = reads(List.of(current));
        String key = referenceKey(current, currentReads, osRefs(List.of(current), currentReads));
        if (!TimingKeys.identity(sidecar).equals(job.identity()) || key == null || !key.equals(job.referenceKey())) {
            return SaveOutcome.DROPPED;
        }
        // The source must match the job: a fit to an OpenSubtitles file names one of the files the job fitted to, and a
        // fit to the film's own track comes from a job that fitted to none.
        if (osFileId == null ? !job.osReferences().isEmpty()
            : job.osReferences().stream().noneMatch(r -> r.osFileId() == osFileId)) {
            return SaveOutcome.DROPPED;
        }
        ReferenceSource source = osFileId == null ? ReferenceSource.EMBEDDED : ReferenceSource.OPENSUBTITLES;
        boolean applies = shouldApply(result, sidecar, current.partIds(), source);
        int old = sidecar.getOffsetMs();
        if (applies) {
            sidecar.setOffsetMs(result.fittedOffsetMs());
            sidecar.setOffsetSource(SubtitleSidecar.OffsetSource.AUTO);
            // A new version of the file: a player that holds the cues asks for them again.
            sidecar.setUpdatedAt(now);
            sidecars.save(sidecar);
            LOG.info("Timing of subtitle {} of film {} set automatically: offset {} ms to {} ms "
                    + "({}% of {} lines line up)", sidecar.getId(), job.filmId(), old, result.fittedOffsetMs(),
                Math.round(result.matchedShare() * 100), result.servedCount());
        }
        ForcedSubtitleAlignment row = alignments.findBySidecarId(sidecar.getId())
            .orElseGet(ForcedSubtitleAlignment::new);
        row.setSidecarId(sidecar.getId());
        row.setSidecarIdentity(job.identity());
        row.setReferenceKey(job.referenceKey());
        row.setReferenceSource(source);
        row.setReferenceOsFileId(osFileId);
        row.setVerdict(result.verdict());
        row.setFittedOffsetMs(result.fittedOffsetMs());
        row.setMatchedShare(result.matchedShare());
        row.setMatchedCount(result.matchedCount());
        row.setServedCount(result.servedCount());
        row.setMedianResidualMs(result.medianResidualMs());
        String reasons = String.join("\n", result.reasons());
        row.setReasons(reasons.length() <= 1000 ? reasons : reasons.substring(0, 1000));
        row.setApplied(source == ReferenceSource.EMBEDDED && result.verdict() == TimingFit.Verdict.ALIGNED
            && sidecar.getOffsetSource() == SubtitleSidecar.OffsetSource.AUTO
            && sidecar.getOffsetMs() == result.fittedOffsetMs());
        row.setCheckedAt(now);
        alignments.save(row);
        return applies ? SaveOutcome.APPLIED : SaveOutcome.SAVED;
    }

    /**
     * Whether a fit sets the sidecar's offset: it is {@code ALIGNED}, it differs from the current offset by at least
     * {@value #APPLY_THRESHOLD_MS} ms, the owner has not verified the file (as the edition's parts are now), and the
     * offset was not set by the owner (a reset to 0 included). Pure.
     *
     * @param result the fit
     * @param sidecar the sidecar as it is now
     * @param partIds the ids of its edition's part files in play order
     * @return true when the offset should be set to the fitted one
     */
    static boolean shouldApply(TimingFit.Result result, SubtitleSidecar sidecar, List<Long> partIds) {
        return shouldApply(result, sidecar, partIds, ReferenceSource.EMBEDDED);
    }

    /**
     * As {@link #shouldApply(TimingFit.Result, SubtitleSidecar, List)}, but a fit against an OpenSubtitles file never
     * sets an offset: two files agreeing says nothing about this copy of the film.
     *
     * @param result the fit
     * @param sidecar the sidecar as it is now
     * @param partIds the ids of its edition's part files in play order
     * @param source what the fit is against
     * @return true when the offset should be set to the fitted one
     */
    static boolean shouldApply(TimingFit.Result result, SubtitleSidecar sidecar, List<Long> partIds,
                               ReferenceSource source) {
        return source == ReferenceSource.EMBEDDED && result.verdict() == TimingFit.Verdict.ALIGNED
            && Math.abs(result.fittedOffsetMs() - sidecar.getOffsetMs()) >= APPLY_THRESHOLD_MS
            && sidecar.getOffsetSource() != SubtitleSidecar.OffsetSource.OWNER
            && !sidecar.isVerifiedFor(partIds);
    }

    /**
     * The current fit of each of some served sidecars, for Admin: only a row whose file and reference still match.
     *
     * @param editionBySidecarId each sidecar's edition (its files loaded)
     * @param servedSidecars the sidecars playback serves
     * @return the fit by sidecar id
     */
    @Transactional(readOnly = true)
    public Map<Long, AlignmentDto> current(Map<Long, Edition> editionBySidecarId,
                                           Collection<SubtitleSidecar> servedSidecars) {
        if (servedSidecars.isEmpty()) {
            return Map.of();
        }
        Map<Long, ForcedSubtitleAlignment> rows = alignments
            .findBySidecarIdIn(servedSidecars.stream().map(SubtitleSidecar::getId).toList()).stream()
            .collect(Collectors.toMap(ForcedSubtitleAlignment::getSidecarId, r -> r));
        List<Served> served = servedSidecars.stream()
            .map(s -> new Served(editionBySidecarId.get(s.getId()), s, null)).toList();
        Map<Long, EmbeddedCueStarts> reads = reads(served);
        Map<Long, OsRefs> os = osRefs(served, reads);
        Map<Long, AlignmentDto> current = new HashMap<>();
        for (Served s : served) {
            ForcedSubtitleAlignment row = rows.get(s.sidecar().getId());
            if (row != null && row.isCurrent(TimingKeys.identity(s.sidecar()), referenceKey(s, reads, os))) {
                boolean embedded = row.getReferenceSource() == ReferenceSource.EMBEDDED;
                current.put(s.sidecar().getId(), new AlignmentDto(row.getVerdict().name(), row.reasonList(),
                    row.getFittedOffsetMs(), row.getMatchedShare(), row.getMatchedCount(), row.getServedCount(),
                    embedded && row.getVerdict() == TimingFit.Verdict.ALIGNED
                        && s.sidecar().getOffsetSource() == SubtitleSidecar.OffsetSource.AUTO
                        && s.sidecar().getOffsetMs() == row.getFittedOffsetMs(), row.getCheckedAt(),
                    row.getReferenceSource().name(), embedded ? null : release(s, os, row.getReferenceOsFileId())));
            }
        }
        return current;
    }

    /** What state a served sidecar's check is in. */
    private State state(Served s, Map<Long, EmbeddedCueStarts> reads, Map<Long, OsRefs> os,
                         ForcedSubtitleAlignment fit) {
        List<MediaFile> refFiles = TimingKeys.referenceFiles(s.edition(), s.sidecar());
        if (refFiles.isEmpty() || refFiles.stream().anyMatch(f -> TimingKeys.fingerprint(f) == null)
            || s.sidecar().isWholeEdition() && TimingKeys.timelineOrNull(s.edition()) == null) {
            return State.SKIP;
        }
        if (!refFiles.stream().allMatch(f -> isRead(f, reads))) {
            return State.NEEDS_READ;
        }
        String key = referenceKey(s, reads, os);
        return fit != null && fit.isCurrent(TimingKeys.identity(s.sidecar()), key) ? State.CURRENT : State.NEEDS_FIT;
    }

    /**
     * What a sidecar's fit is made of: the film files' embedded reads (see {@link TimingKeys#referenceKey}) and, when
     * the film has no English track of its own, the usable stored OpenSubtitles files it is fitted to, as
     * {@code |os:<imdbId>:<their file ids, sorted>}. A new stored file, a new IMDb id or a re-rip makes the row stale.
     */
    private static String referenceKey(Served s, Map<Long, EmbeddedCueStarts> reads, Map<Long, OsRefs> os) {
        String key = TimingKeys.referenceKey(s.edition(), s.sidecar(), reads);
        List<ReferenceFacts> downloaded = key == null ? List.of() : downloadedFor(s, os);
        if (downloaded.isEmpty()) {
            return key;
        }
        return key + "|os:" + os.get(s.edition().getId()).imdbId() + ":" + downloaded.stream()
            .map(ReferenceFacts::osFileId).sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** The usable stored OpenSubtitles files for a sidecar: never its own file (it cannot vouch for itself). */
    private static List<ReferenceFacts> downloadedFor(Served s, Map<Long, OsRefs> os) {
        OsRefs refs = os.get(s.edition().getId());
        return refs == null ? List.of() : refs.usable().stream()
            .filter(r -> !Objects.equals(r.osFileId(), s.sidecar().getOpenSubtitlesFileId())).toList();
    }

    private static String release(Served s, Map<Long, OsRefs> os, Long osFileId) {
        return downloadedFor(s, os).stream().filter(r -> Objects.equals(r.osFileId(), osFileId)).findFirst()
            .map(ReferenceFacts::release).orElse(null);
    }

    /**
     * For each served edition the timing check fits to OpenSubtitles files (a single file whose embedded read found no
     * English track, of a film searched for full subtitles under its current IMDb id that the owner has not marked
     * {@code NONE_EXIST}, as {@link TimingReferenceService} does), the usable stored files among
     * its candidates: the same selection as {@link TimingReferenceService}, through {@link TimingReferenceRule} and
     * {@link CreditsStarts}. This runs on the playback path ({@link #current}), so it reads facts only (no cue text,
     * nothing parsed) and the credits of all those editions in one query; a reference with no kept last-cue end (stored
     * before V30) is not used.
     */
    private Map<Long, OsRefs> osRefs(List<Served> served, Map<Long, EmbeddedCueStarts> reads) {
        Map<Long, Edition> wanted = new LinkedHashMap<>();
        for (Served s : served) {
            Edition e = s.edition();
            Film film = e.getFilm();
            List<MediaFile> parts = e.originalParts();
            if (parts.size() == 1 && e.getRuntimeMs() > 0 && film.getImdbId() != null
                && film.getFullNeedOwner() != FullNeedChoice.NONE_EXIST
                && film.getOsFullCandidates() != null && film.getImdbId().equals(film.getOsFullSearchedImdbId())
                && isRead(parts.get(0), reads)
                && reads.get(parts.get(0).getId()).getStatus() == EmbeddedCueStarts.Status.NO_REFERENCE) {
                wanted.putIfAbsent(e.getId(), e);
            }
        }
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ReferenceFacts>> storedByFilm = new HashMap<>();
        Set<Long> filmIds = wanted.values().stream().map(e -> e.getFilm().getId()).collect(Collectors.toSet());
        for (ReferenceFacts r : references.findFactsByFilmIdIn(filmIds)) {
            storedByFilm.computeIfAbsent(r.filmId(), k -> new ArrayList<>()).add(r);
        }
        Map<Long, Long> creditsStarts = CreditsStarts.ofAll(wanted.values(), credits);
        Map<Long, OsRefs> found = new HashMap<>();
        for (Edition e : wanted.values()) {
            Film film = e.getFilm();
            Map<Long, ReferenceFacts> stored = new HashMap<>();
            storedByFilm.getOrDefault(film.getId(), List.of()).stream()
                .filter(r -> film.getImdbId().equals(r.imdbId())).forEach(r -> stored.put(r.osFileId(), r));
            List<OpenSubtitlesResult> candidates = TimingReferenceRule.candidates(film.getOsFullCandidates(),
                e.getName());
            List<ReferenceFacts> usable = TimingReferenceRule.usableReferences(candidates, stored, e.getRuntimeMs(),
                creditsStarts.get(e.getId()));
            if (!usable.isEmpty()) {
                found.put(e.getId(), new OsRefs(film.getImdbId(), usable));
            }
        }
        return found;
    }

    private static boolean isRead(MediaFile file, Map<Long, EmbeddedCueStarts> reads) {
        EmbeddedCueStarts read = reads.get(file.getId());
        return read != null && read.getFingerprint().equals(TimingKeys.fingerprint(file));
    }

    /** The sidecars playback serves for the editions of some films (the same pick as playback). */
    private List<Served> served(Collection<Long> filmIds) {
        if (filmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> found = editions.findWithFilesByFilmIdIn(filmIds);
        List<Long> allIds = found.stream().flatMap(e -> partIds(e).stream()).toList();
        if (allIds.isEmpty()) {
            return List.of();
        }
        List<SubtitleSidecar> candidates = sidecars.findByMediaFileIdInAndKind(allIds, SubtitleKind.FORCED);
        List<Served> served = new ArrayList<>();
        for (Edition edition : found) {
            List<Long> partIds = partIds(edition);
            Pick pick = SidecarPrecedence.pick(partIds, candidates, SubtitleKind.FORCED);
            for (Slot slot : pick.slots()) {
                served.add(new Served(edition, slot.sidecar(), partIds));
            }
            // A download is fitted whether or not it plays now: its proof is what lets it win (the owner's file may
            // be the one playing until then), and the fit is read from the film's own track, which costs nothing more.
            for (SubtitleSidecar download : Downloads.of(edition, partIds, candidates, SubtitleKind.FORCED)) {
                served.add(new Served(edition, download, partIds));
            }
        }
        return served;
    }

    private Map<Long, EmbeddedCueStarts> reads(List<Served> served) {
        Set<Long> fileIds = served.stream()
            .flatMap(s -> TimingKeys.referenceFiles(s.edition(), s.sidecar()).stream()).map(MediaFile::getId)
            .collect(Collectors.toSet());
        Map<Long, EmbeddedCueStarts> reads = new HashMap<>();
        cache.findAllById(fileIds).forEach(r -> reads.put(r.getMediaFileId(), r));
        return reads;
    }

    private Map<Long, ForcedSubtitleAlignment> fits(List<Served> served) {
        Map<Long, ForcedSubtitleAlignment> fits = new HashMap<>();
        if (served.isEmpty()) {
            return fits;
        }
        alignments.findBySidecarIdIn(served.stream().map(s -> s.sidecar().getId()).toList())
            .forEach(a -> fits.put(a.getSidecarId(), a));
        return fits;
    }

    private static List<Long> partIds(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }
}
