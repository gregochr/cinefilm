package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.MediaFileRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SubtitleDtos.AttachRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CueDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CuesDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.OffsetRequest;
import gregochr.cinefilm.subtitle.SubtitleDtos.SidecarDto;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin's view of the forced subtitle files and the owner's actions on them: attach one to an edition (and part),
 * give it back to automatic matching, nudge its timing, and say it looks right. Every action takes the row id of a
 * file and, for an attach, the id of an edition: nothing takes a path. A nudge or a re-attach takes the owner's check
 * away; so does any change to the edition's parts (see {@link SubtitleSidecar#isVerifiedFor}).
 */
@Service
public class SubtitleAdminService {

    /** The largest nudge either way, in milliseconds (ten minutes). */
    public static final int MAX_OFFSET_MS = 600_000;

    private final SubtitleSidecarRepository sidecars;
    private final MediaFileRepository files;
    private final EditionRepository editions;
    private final SidecarSync sync;
    private final ForcedSubtitleCheckRepository checks;
    private final OpenSubtitlesReferenceRepository references;
    private final Clock clock;
    private final LibraryLock lock;
    private final TransactionTemplate tx;

    /** Creates the service. */
    public SubtitleAdminService(SubtitleSidecarRepository sidecars, MediaFileRepository files,
                                EditionRepository editions, SidecarSync sync, ForcedSubtitleCheckRepository checks,
                                OpenSubtitlesReferenceRepository references, Clock clock, LibraryLock lock,
                                PlatformTransactionManager transactions) {
        this.sidecars = sidecars;
        this.files = files;
        this.editions = editions;
        this.sync = sync;
        this.checks = checks;
        this.references = references;
        this.clock = clock;
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Every forced subtitle file with where it belongs and how it reads. */
    @Transactional(readOnly = true)
    public List<SidecarDto> list() {
        return build(sidecars.findAllByOrderByPathAscIdAsc());
    }

    /**
     * A file's cues on the edition's timeline, its offset applied (a cue never starts before 0).
     *
     * @param id the file's row id
     * @return the offset and the cues
     * @throws NotFoundException when there is no such file
     */
    @Transactional(readOnly = true)
    public CuesDto cues(Long id) {
        SubtitleSidecar sidecar = find(id);
        long start = 0;
        MediaFile file = file(sidecar);
        if (file != null) {
            start = slotStartMs(file.getEdition(), file, sidecar.isWholeEdition());
        }
        long shift = start + sidecar.getOffsetMs();
        List<CueDto> cues = SrtParser.cues(sidecar.getCuesText()).stream()
            .map(c -> new CueDto(Math.max(0, c.startMs() + shift), Math.max(0, c.endMs() + shift), c.text()))
            .toList();
        return new CuesDto(sidecar.getOffsetMs(), cues);
    }

    /**
     * Attaches a file to an edition: for the whole edition (part null; stored against part 1) or for one part. The
     * scan never matches it again by name.
     *
     * @param id the file's row id
     * @param request the edition and the part
     * @return the file as listed afterwards
     * @throws NotFoundException when the file or the edition is unknown
     * @throws BadRequestException when there is no edition id, the edition has no film file or has no such part
     */
    public SidecarDto attach(Long id, AttachRequest request) {
        return locked(() -> attachLocked(id, request));
    }

    private SidecarDto attachLocked(Long id, AttachRequest request) {
        SubtitleSidecar sidecar = find(id);
        if (request == null || request.editionId() == null) {
            throw new BadRequestException("Pick an edition");
        }
        Edition edition = editions.findById(request.editionId())
            .orElseThrow(() -> new NotFoundException("No such edition"));
        List<MediaFile> parts = edition.originalParts();
        if (parts.isEmpty()) {
            throw new BadRequestException("That edition has no film file");
        }
        MediaFile target = request.part() == null ? parts.get(0)
            : parts.stream().filter(p -> p.getPartNumber() == request.part()).findFirst()
                .orElseThrow(() -> new BadRequestException("That edition has no part " + request.part()));
        sidecar.setMediaFileId(target.getId());
        // A single file is timed from its own start, so it stays right if another file is joined before it later.
        sidecar.setWholeEdition(request.part() == null && parts.size() > 1);
        sidecar.setOwnerAttached(true);
        changed(sidecar);
        return one(id);
    }

    /**
     * Takes the owner's attachment away: the file goes back to being matched by name, from the next scan and from
     * now.
     *
     * @param id the file's row id
     * @return the file as listed afterwards
     * @throws NotFoundException when there is no such file
     */
    public SidecarDto detach(Long id) {
        return locked(() -> detachLocked(id));
    }

    private SidecarDto detachLocked(Long id) {
        SubtitleSidecar sidecar = find(id);
        sidecar.setOwnerAttached(false);
        sidecar.setMediaFileId(null);
        sidecar.setWholeEdition(false);
        changed(sidecar);
        sync.rematch(sidecar);
        return one(id);
    }

    /**
     * Sets the file's timing offset.
     *
     * @param id the file's row id
     * @param request the offset in milliseconds, within {@link #MAX_OFFSET_MS} either way
     * @return the file as listed afterwards
     * @throws NotFoundException when there is no such file
     * @throws BadRequestException when the offset is missing or too large
     */
    public SidecarDto offset(Long id, OffsetRequest request) {
        return locked(() -> offsetLocked(id, request));
    }

    private SidecarDto offsetLocked(Long id, OffsetRequest request) {
        SubtitleSidecar sidecar = find(id);
        if (request == null || request.offsetMs() == null) {
            throw new BadRequestException("An offset is required");
        }
        if (request.offsetMs() < -MAX_OFFSET_MS || request.offsetMs() > MAX_OFFSET_MS) {
            throw new BadRequestException("The offset must be within 10 minutes either way");
        }
        sidecar.setOffsetMs(request.offsetMs());
        changed(sidecar);
        return one(id);
    }

    /**
     * Records that the owner has checked the lines and the timing, against the edition's parts as they are now.
     *
     * @param id the file's row id
     * @return the file as listed afterwards
     * @throws NotFoundException when there is no such file
     * @throws BadRequestException when the file is not attached to a film yet
     */
    public SidecarDto verify(Long id) {
        return locked(() -> verifyLocked(id));
    }

    private SidecarDto verifyLocked(Long id) {
        SubtitleSidecar sidecar = find(id);
        MediaFile file = file(sidecar);
        if (file == null) {
            throw new BadRequestException("Attach the subtitle to a film first");
        }
        Instant now = clock.instant();
        sidecar.setOwnerVerifiedAt(now);
        sidecar.setVerifiedParts(SubtitleSidecar.partsKey(partIds(file.getEdition())));
        sidecar.setUpdatedAt(now);
        return one(id);
    }

    /**
     * Takes the owner's check away.
     *
     * @param id the file's row id
     * @return the file as listed afterwards
     * @throws NotFoundException when there is no such file
     */
    public SidecarDto unverify(Long id) {
        return locked(() -> unverifyLocked(id));
    }

    private SidecarDto unverifyLocked(Long id) {
        SubtitleSidecar sidecar = find(id);
        sidecar.clearVerification();
        sidecar.setUpdatedAt(clock.instant());
        return one(id);
    }

    /**
     * A scan rewrites the sidecar rows, so a change is refused (409) while one runs, as Fix match is. The lock is
     * taken outside the transaction and given back only after it has committed.
     */
    private SidecarDto locked(Supplier<SidecarDto> work) {
        if (!lock.tryAcquire(LibraryLock.SUBTITLE)) {
            throw new ConflictException("A scan is running");
        }
        try {
            return tx.execute(status -> work.get());
        } finally {
            lock.release();
        }
    }

    private void changed(SubtitleSidecar sidecar) {
        sidecar.clearVerification();
        sidecar.setUpdatedAt(clock.instant());
    }

    private SubtitleSidecar find(Long id) {
        return sidecars.findById(id).orElseThrow(() -> new NotFoundException("No such subtitle"));
    }

    private MediaFile file(SubtitleSidecar sidecar) {
        return sidecar.getMediaFileId() == null ? null : files.findById(sidecar.getMediaFileId()).orElse(null);
    }

    private SidecarDto one(Long id) {
        sidecars.flush();
        return list().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private List<SidecarDto> build(List<SubtitleSidecar> all) {
        Set<Long> fileIds = all.stream().map(SubtitleSidecar::getMediaFileId).filter(i -> i != null)
            .collect(Collectors.toSet());
        Map<Long, MediaFile> fileById = new HashMap<>();
        files.findAllById(fileIds).forEach(f -> fileById.put(f.getId(), f));
        Map<Long, Edition> editionById = new HashMap<>();
        Map<Long, List<SubtitleSidecar>> byEdition = new HashMap<>();
        for (SubtitleSidecar sidecar : all) {
            MediaFile file = fileById.get(sidecar.getMediaFileId());
            if (file != null) {
                Edition edition = file.getEdition();
                editionById.put(edition.getId(), edition);
                byEdition.computeIfAbsent(edition.getId(), k -> new ArrayList<>()).add(sidecar);
            }
        }
        Set<Long> conflicting = new HashSet<>();
        Map<Long, String> versions = new HashMap<>();
        Set<Long> served = new HashSet<>();
        byEdition.forEach((editionId, group) -> {
            Edition edition = editionById.get(editionId);
            Pick pick = SidecarPrecedence.pick(partIds(edition), group);
            conflicting.addAll(pick.conflicting());
            String version = pick.slots().isEmpty() ? null : ServedSubtitles.version(edition, pick);
            if (version != null) {
                versions.put(editionId, version);
                pick.slots().forEach(slot -> served.add(slot.sidecar().getId()));
            }
        });
        Map<Long, CheckDto> checkByEdition = currentChecks(versions, editionById);
        List<SidecarDto> dtos = new ArrayList<>();
        for (SubtitleSidecar sidecar : all) {
            MediaFile file = fileById.get(sidecar.getMediaFileId());
            CheckDto check = file == null || !served.contains(sidecar.getId()) ? null
                : checkByEdition.get(file.getEdition().getId());
            dtos.add(dto(sidecar, file, conflicting.contains(sidecar.getId()), check));
        }
        return dtos;
    }

    /**
     * The checks that still describe what their edition serves (the same served version, a reference fetched for the
     * film's current IMDb id), by edition id. Two queries however many editions there are.
     */
    private Map<Long, CheckDto> currentChecks(Map<Long, String> versions, Map<Long, Edition> editionById) {
        if (versions.isEmpty()) {
            return Map.of();
        }
        List<ForcedSubtitleCheck> found = checks.findByEditionIdIn(versions.keySet());
        Map<Long, OpenSubtitlesReference> referenceById = new HashMap<>();
        references.findAllById(found.stream().map(ForcedSubtitleCheck::getReferenceId).toList())
            .forEach(r -> referenceById.put(r.getId(), r));
        Map<Long, CheckDto> current = new HashMap<>();
        for (ForcedSubtitleCheck check : found) {
            Long editionId = check.getEditionId();
            OpenSubtitlesReference reference = referenceById.get(check.getReferenceId());
            if (check.isCurrent(versions.get(editionId), editionById.get(editionId).getFilm().getImdbId(),
                reference)) {
                current.put(editionId, new CheckDto(check.getVerdict(), check.reasonList(), check.getBestOffsetMs(),
                    check.getMatchedShare(), check.getServedCount(), check.getReferenceCount(),
                    reference.getRelease(), check.getCheckedAt()));
            }
        }
        return current;
    }

    private static SidecarDto dto(SubtitleSidecar s, MediaFile file, boolean conflict, CheckDto check) {
        if (file == null) {
            return new SidecarDto(s.getId(), s.getPath(), s.getPresence().name(), "unmatched", null, null, null, null,
                0, null, false, s.isOwnerAttached(), s.getCueCount(), s.getParseReport(), s.getOffsetMs(), false,
                false, s.getUpdatedAt().toEpochMilli(), null);
        }
        Edition edition = file.getEdition();
        List<Long> partIds = partIds(edition);
        long start = slotStartMs(edition, file, s.isWholeEdition());
        boolean endsAfter = edition.getRuntimeMs() > 0 && s.getLastEndMs() > 0
            && start + s.getLastEndMs() + s.getOffsetMs() > edition.getRuntimeMs();
        return new SidecarDto(s.getId(), s.getPath(), s.getPresence().name(), conflict ? "conflict" : "matched",
            edition.getFilm().getId(), edition.getFilm().getTitle(), edition.getId(), edition.getName(),
            partIds.size(), s.isWholeEdition() ? null : file.getPartNumber(), s.isWholeEdition(),
            s.isOwnerAttached(), s.getCueCount(), s.getParseReport(), s.getOffsetMs(), s.isVerifiedFor(partIds),
            endsAfter, s.getUpdatedAt().toEpochMilli(), check);
    }

    private static List<Long> partIds(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }

    /** Where a file's cues start on the edition's timeline: 0 for a whole-edition file and for part 1. */
    private static long slotStartMs(Edition edition, MediaFile file, boolean whole) {
        int index = partIds(edition).indexOf(file.getId());
        if (whole || index <= 0) {
            return 0;
        }
        try {
            return EditionTimeline.of(edition).partStartMs(index);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
