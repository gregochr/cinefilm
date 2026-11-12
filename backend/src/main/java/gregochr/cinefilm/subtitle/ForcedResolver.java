package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.film.SubtitleStream;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedChoice.Decision;
import gregochr.cinefilm.subtitle.ForcedChoice.DownloadSide;
import gregochr.cinefilm.subtitle.ForcedChoice.FileSide;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.SidecarTrust.Assessment;
import gregochr.cinefilm.subtitle.SubtitleDtos.AlignmentDto;
import gregochr.cinefilm.subtitle.SubtitleDtos.CheckDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of choosing which forced subtitle an edition plays ({@link ForcedChoice}): for each edition it
 * gathers the owner's files ({@link SidecarPrecedence}), the downloads ({@link Downloads}), the checks and timing fits
 * that still describe them, and the embedded track, assesses each ({@link SidecarTrust}) and decides. Playback, Admin,
 * the need list and the download refresher all ask this one place, so they cannot disagree.
 *
 * <p>The owner's files and each download are checked on their own: a file's comparison with OpenSubtitles is the
 * edition's {@link ForcedSubtitleCheck} for the files' own pick (not for what is finally served), and a download's is
 * its own {@link ForcedDownloadCheck}. That is what stops the choice flipping back and forth: replacing a failed file
 * with a download does not take the file's failure away. A fixed number of queries however many editions there are.
 */
@Service
public class ForcedResolver {

    private final SubtitleSidecarRepository sidecars;
    private final ForcedSubtitleCheckRepository checks;
    private final ForcedDownloadCheckRepository downloadChecks;
    private final OpenSubtitlesReferenceRepository references;
    private final SidecarTimingService timing;

    /** Creates the resolver. */
    public ForcedResolver(SubtitleSidecarRepository sidecars, ForcedSubtitleCheckRepository checks,
                          ForcedDownloadCheckRepository downloadChecks,
                          OpenSubtitlesReferenceRepository references, SidecarTimingService timing) {
        this.sidecars = sidecars;
        this.checks = checks;
        this.downloadChecks = downloadChecks;
        this.references = references;
        this.timing = timing;
    }

    /**
     * A download with where it stands.
     *
     * @param sidecar the download
     * @param assessment proven, unchecked or failed
     * @param check its comparison with another OpenSubtitles file while that still describes it, else null
     * @param alignment its fit to the film's own English track while that still describes it, else null
     * @param version its served version (what a check of it was made for), or null when a later part's start is unknown
     */
    public record DownloadState(SubtitleSidecar sidecar, Assessment assessment, CheckDto check, AlignmentDto alignment,
                                String version) {
    }

    /**
     * Everything known about one edition's forced subtitles.
     *
     * @param edition the edition
     * @param partIds its part files in play order
     * @param filePick the owner's files as playback would serve them (no slots when there is none or they conflict)
     * @param fileVersion the served version of {@code filePick}, or null when it has no slots or a later part's start
     *     is unknown
     * @param fileAssessment where the owner's files stand, or null when {@code filePick} has no slots
     * @param fileCheck the files' comparison with OpenSubtitles while that still describes them, else null
     * @param fileReferenceFileId the OpenSubtitles file id {@code fileCheck} was compared with, or null when there is
     *     no current check
     * @param fileCheckCutTags the cut tags of the edition's name when {@code fileCheck} was made, or null when there is
     *     no current check
     * @param alignments the current timing fit of each of the files and downloads, by sidecar id
     * @param downloads the live downloads, lowest id first
     * @param embedded true when a file of the edition has an English (or unlabelled) forced text track
     * @param decision which subtitle plays
     * @param reasons why each subtitle of the edition plays or not, by sidecar id
     */
    public record Resolution(Edition edition, List<Long> partIds, Pick filePick, String fileVersion,
                             Assessment fileAssessment, CheckDto fileCheck, Long fileReferenceFileId,
                             String fileCheckCutTags,
                             Map<Long, AlignmentDto> alignments,
                             List<DownloadState> downloads, boolean embedded, Decision decision,
                             Map<Long, String> reasons) {

        /** What playback serves as cues: the owner's files, the playing download, or nothing (an empty pick). */
        Pick chosen() {
            return switch (decision.source()) {
                case FILE -> filePick;
                case DOWNLOAD -> downloads.stream().filter(d -> d.sidecar().getId().equals(decision.downloadId()))
                    .findFirst().map(d -> Downloads.pick(d.sidecar())).orElseThrow();
                case EMBEDDED, NONE -> new Pick(List.of(), Set.of());
            };
        }

        /** True when two of the owner's files compete for the same place, so none of them plays. */
        public boolean fileConflict() {
            return !filePick.conflicting().isEmpty();
        }
    }

    /**
     * Resolves some editions.
     *
     * @param editions the editions, with their files and film loaded
     * @return the resolution by edition id (an edition with no part files is resolved to nothing playing)
     */
    @Transactional(readOnly = true)
    public Map<Long, Resolution> resolve(Collection<Edition> editions) {
        List<Edition> list = List.copyOf(editions);
        List<Long> allIds = list.stream().flatMap(e -> partIds(e).stream()).toList();
        List<SubtitleSidecar> found = allIds.isEmpty() ? List.of() : sidecars.findByMediaFileIdIn(allIds);
        Map<Long, Pick> filePicks = new HashMap<>();
        Map<Long, List<SubtitleSidecar>> downloadsBy = new HashMap<>();
        Map<Long, Edition> editionBySidecarId = new HashMap<>();
        List<SubtitleSidecar> toAlign = new ArrayList<>();
        for (Edition edition : list) {
            List<Long> partIds = partIds(edition);
            Pick pick = SidecarPrecedence.pick(partIds, found);
            filePicks.put(edition.getId(), pick);
            List<SubtitleSidecar> downloads = Downloads.of(edition, partIds, found);
            downloadsBy.put(edition.getId(), downloads);
            for (SubtitleSidecar s : pick.slots().stream().map(Slot::sidecar).toList()) {
                editionBySidecarId.put(s.getId(), edition);
                toAlign.add(s);
            }
            for (SubtitleSidecar d : downloads) {
                editionBySidecarId.put(d.getId(), edition);
                toAlign.add(d);
            }
        }
        Map<Long, AlignmentDto> alignmentBySidecar = timing.current(editionBySidecarId, toAlign);
        Map<Long, ForcedSubtitleCheck> checkByEdition = new HashMap<>();
        checks.findByEditionIdIn(list.stream().map(Edition::getId).toList())
            .forEach(c -> checkByEdition.put(c.getEditionId(), c));
        Map<Long, ForcedDownloadCheck> checkByDownload = new HashMap<>();
        List<Long> downloadIds = downloadsBy.values().stream().flatMap(List::stream).map(SubtitleSidecar::getId)
            .toList();
        if (!downloadIds.isEmpty()) {
            downloadChecks.findBySidecarIdIn(downloadIds).forEach(c -> checkByDownload.put(c.getSidecarId(), c));
        }
        Set<Long> referenceIds = new HashSet<>();
        checkByEdition.values().forEach(c -> referenceIds.add(c.getReferenceId()));
        checkByDownload.values().forEach(c -> referenceIds.add(c.getReferenceId()));
        Map<Long, OpenSubtitlesReference> referenceById = new HashMap<>();
        references.findAllById(referenceIds).forEach(r -> referenceById.put(r.getId(), r));

        Map<Long, Resolution> resolutions = new HashMap<>();
        for (Edition edition : list) {
            resolutions.put(edition.getId(), resolution(edition, found, filePicks.get(edition.getId()),
                downloadsBy.get(edition.getId()), checkByEdition.get(edition.getId()), checkByDownload,
                referenceById, alignmentBySidecar));
        }
        return resolutions;
    }

    private Resolution resolution(Edition edition, List<SubtitleSidecar> found, Pick filePick,
                                  List<SubtitleSidecar> downloads, ForcedSubtitleCheck fileRow,
                                  Map<Long, ForcedDownloadCheck> downloadRows,
                                  Map<Long, OpenSubtitlesReference> referenceById,
                                  Map<Long, AlignmentDto> alignmentBySidecar) {
        List<Long> partIds = partIds(edition);
        Film film = edition.getFilm();
        boolean hasFile = !filePick.slots().isEmpty();
        String fileVersion = hasFile ? ServedSubtitles.version(edition, filePick) : null;
        CheckDto fileCheck = fileRow == null ? null : currentCheck(fileRow, fileVersion, film, referenceById);
        Assessment fileAssessment = null;
        FileSide fileSide = null;
        Map<Long, AlignmentDto> alignments = new HashMap<>();
        if (hasFile) {
            boolean verified = filePick.slots().stream().allMatch(s -> s.sidecar().isVerifiedFor(partIds));
            boolean aligned = filePick.slots().stream()
                .allMatch(s -> SidecarTrust.isAligned(alignmentBySidecar.get(s.sidecar().getId())));
            filePick.slots().forEach(s -> putIfPresent(alignments, s.sidecar().getId(), alignmentBySidecar));
            fileAssessment = SidecarTrust.assess(verified, fileCheck == null ? null : fileCheck.verdict(), aligned);
            fileSide = new FileSide(fileAssessment,
                filePick.slots().stream().mapToInt(s -> s.sidecar().getCueCount()).sum());
        }
        List<DownloadState> states = new ArrayList<>();
        List<DownloadSide> sides = new ArrayList<>();
        for (SubtitleSidecar d : downloads) {
            String version = ServedSubtitles.version(edition, Downloads.pick(d));
            ForcedDownloadCheck row = downloadRows.get(d.getId());
            CheckDto check = row == null ? null : currentCheck(row, version, film, referenceById);
            AlignmentDto alignment = alignmentBySidecar.get(d.getId());
            putIfPresent(alignments, d.getId(), alignmentBySidecar);
            Assessment assessment = SidecarTrust.assess(d.isVerifiedFor(partIds),
                check == null ? null : check.verdict(), SidecarTrust.isAligned(alignment));
            states.add(new DownloadState(d, assessment, check, alignment, version));
            sides.add(new DownloadSide(d.getId(), assessment));
        }
        boolean embedded = hasEmbeddedForced(edition);
        Decision decision = ForcedChoice.decide(new ForcedChoice.Input(fileSide, sides, embedded));
        Map<Long, String> reasons = reasons(edition, found, filePick, decision);
        Long fileReferenceFileId = fileCheck == null ? null : referenceById.get(fileRow.getReferenceId()).getOsFileId();
        String fileCheckCutTags = fileCheck == null ? null : fileRow.getCutTags();
        return new Resolution(edition, partIds, filePick, fileVersion, fileAssessment, fileCheck, fileReferenceFileId,
            fileCheckCutTags,
            Map.copyOf(alignments), List.copyOf(states), embedded, decision, reasons);
    }

    private static void putIfPresent(Map<Long, AlignmentDto> into, Long id, Map<Long, AlignmentDto> from) {
        AlignmentDto alignment = from.get(id);
        if (alignment != null) {
            into.put(id, alignment);
        }
    }

    /**
     * The check when it still describes what was checked: made for this served version, by the current rules, against a
     * reference fetched for the film's current IMDb id, while the film's best OpenSubtitles result is still the single
     * file it was.
     */
    private static CheckDto currentCheck(SubtitleCheckBase row, String version, Film film,
                                         Map<Long, OpenSubtitlesReference> referenceById) {
        OpenSubtitlesResult best = film.getOsBest() == null ? null : OpenSubtitlesResult.fromJson(film.getOsBest());
        OpenSubtitlesReference reference = referenceById.get(row.getReferenceId());
        if (best == null || !best.isSingleFile()
            || !row.isCurrent(version, film.getImdbId(), reference, best.fileId())) {
            return null;
        }
        return new CheckDto(row.getVerdict(), row.reasonList(), row.getBestOffsetMs(), row.getMatchedShare(),
            row.getServedCount(), row.getReferenceCount(), reference.getRelease(), row.getCheckedAt());
    }

    /** Why each subtitle attached to the edition plays or does not. */
    private static Map<Long, String> reasons(Edition edition, List<SubtitleSidecar> found, Pick filePick,
                                             Decision decision) {
        List<Long> partIds = partIds(edition);
        Map<Long, String> reasons = new HashMap<>();
        Set<Long> slotIds = new HashSet<>();
        filePick.slots().forEach(s -> slotIds.add(s.sidecar().getId()));
        for (SubtitleSidecar s : found) {
            // `found` holds the subtitles of every edition resolved together: only this edition's get a reason here.
            if (!partIds.contains(s.getMediaFileId())) {
                continue;
            }
            if (s.isDownload()) {
                String reason = decision.downloadReasons().get(s.getId());
                reasons.put(s.getId(), reason != null ? reason
                    : s.getPresence() != Presence.PRESENT ? "Not used: the download is missing"
                    : !Downloads.madeFor(edition, s) ? "Not used: it was made for another edition"
                    : "Not used: it was fetched for another IMDb id");
            } else if (slotIds.contains(s.getId())) {
                reasons.put(s.getId(), decision.fileReason());
            } else if (filePick.conflicting().contains(s.getId())) {
                reasons.put(s.getId(), "Not used: two files compete for the same place, so neither plays");
            } else if (s.getPresence() != Presence.PRESENT) {
                reasons.put(s.getId(), "Not used: the file is missing");
            } else if (s.getCueCount() == 0) {
                reasons.put(s.getId(), "Not used: it has no lines");
            } else {
                reasons.put(s.getId(), "Not used: a whole-edition file takes its place");
            }
        }
        return Map.copyOf(reasons);
    }

    /** True when a present file of the edition has a forced English (or unlabelled) text subtitle track. */
    private static boolean hasEmbeddedForced(Edition edition) {
        for (MediaFile file : edition.originalParts()) {
            if (file.getPresence() != Presence.PRESENT) {
                continue;
            }
            for (SubtitleStream stream : file.getSubtitleStreams()) {
                String language = stream.getLanguage() == null ? null : stream.getLanguage().toLowerCase(Locale.ROOT);
                if (stream.isForced() && (language == null || "eng".equals(language) || "en".equals(language)
                    || "und".equals(language))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Long> partIds(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }
}
