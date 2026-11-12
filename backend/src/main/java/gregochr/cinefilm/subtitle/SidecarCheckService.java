package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.opensubtitles.CutTag;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedResolver.DownloadState;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarTrust.Trust;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of checking forced subtitles against OpenSubtitles: which are due (the owner's files of an edition,
 * and its downloads that nothing cheaper has proven), and saving a result. Kept apart from
 * {@link SidecarCheckRefresher} (which talks to OpenSubtitles) so each can be tested
 * on its own.
 */
@Service
public class SidecarCheckService {

    private final FilmRepository films;
    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;
    private final ForcedResolver resolver;
    private final ForcedSubtitleCheckRepository checks;
    private final ForcedDownloadCheckRepository downloadChecks;
    private final ForcedCheckCandidateRepository candidates;
    private final boolean timingCheckEnabled;

    /** The longest reasons text the column holds. */
    static final int MAX_REASONS_CHARS = 1000;

    /** Creates the service; a download waits for its timing fit before it is compared unless that check is off. */
    public SidecarCheckService(FilmRepository films, SubtitleSidecarRepository sidecars, EditionRepository editions,
                               ForcedResolver resolver, ForcedSubtitleCheckRepository checks,
                               ForcedDownloadCheckRepository downloadChecks,
                               ForcedCheckCandidateRepository candidates,
                               @Value("${cinefilm.subtitles.timing-check.enabled:true}") boolean timingCheckEnabled) {
        this.films = films;
        this.sidecars = sidecars;
        this.editions = editions;
        this.resolver = resolver;
        this.checks = checks;
        this.downloadChecks = downloadChecks;
        this.candidates = candidates;
        this.timingCheckEnabled = timingCheckEnabled;
    }

    /**
     * The subtitles to check: the film has results from a search of its current IMDb id that also stored its
     * candidates (a film searched before they were kept waits for the search to run again), and either
     * <ul>
     *   <li>the edition has owner's files that playback would pick and no check of them still describes them (made by
     *       the current rules, same version, a reference for the film's current IMDb id that is still one of the
     *       film's candidates), or</li>
     *   <li>the edition has a download that is not proven, whose timing fit exists and did not prove it (or the timing
     *       check is off), that has no current check, and for which OpenSubtitles has another single file to compare
     *       with: the cheapest proof is the timing fit, so this second opinion is asked for only when it could
     *       not prove it.</li>
     * </ul>
     * The owner's unverified files come first, then the verified ones (still worth checking for completeness); then by
     * film and edition, an edition's own files before its downloads. Each due item carries the candidates in the order
     * to try them for its own cut ({@link OpenSubtitlesRanking#rankFor}) and the files its edition has already been
     * compared with.
     *
     * @return the due checks in the order to make them
     */
    @Transactional(readOnly = true)
    public List<DueCheck> due() {
        Map<Long, OsBestRef> best = new HashMap<>();
        for (OsBestRef ref : films.findOpenSubtitlesBest()) {
            best.put(ref.id(), ref);
        }
        Set<Long> filmIds = new HashSet<>(sidecars.findFilmIdsWithPresentSubtitle());
        filmIds.retainAll(best.keySet());
        if (filmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(filmIds);
        Map<Long, Resolution> resolved = resolver.resolve(fetched);
        List<ForcedCheckCandidate> ledger = candidates.findByEditionIdIn(resolved.keySet());
        List<DueCheck> due = new ArrayList<>();
        for (Edition edition : fetched) {
            Resolution resolution = resolved.get(edition.getId());
            OsBestRef ref = best.get(edition.getFilm().getId());
            // A film searched before its candidates were kept is not checked until the search has written them.
            if (ref.osCandidates() == null) {
                continue;
            }
            List<OpenSubtitlesResult> filmCandidates = singleFile(ref.osCandidates());
            Set<Long> tried = new HashSet<>();
            ledger.stream().filter(l -> l.getEditionId().equals(edition.getId()) && l.getImdbId().equals(ref.imdbId()))
                .forEach(l -> tried.add(l.getOsFileId()));
            addFileCheck(due, edition, resolution, ref, filmCandidates, Set.copyOf(tried));
            for (DownloadState download : resolution.downloads()) {
                addDownloadCheck(due, edition, download, ref, filmCandidates, Set.copyOf(tried));
            }
        }
        due.sort(Comparator.comparing(DueCheck::ownerVerified).thenComparing(DueCheck::filmId)
            .thenComparing(DueCheck::editionId).thenComparing(DueCheck::isDownload));
        return due;
    }

    private void addFileCheck(List<DueCheck> due, Edition edition, Resolution resolution, OsBestRef ref,
                              List<OpenSubtitlesResult> filmCandidates, Set<Long> tried) {
        if (resolution.filePick().slots().isEmpty()) {
            return;
        }
        boolean current = resolution.fileCheck() != null && filmCandidates.stream()
            .anyMatch(c -> c.fileId() == resolution.fileReferenceFileId().longValue())
            && resolution.fileCheckCutTags().equals(CutTag.key(edition.getName()));
        if (current) {
            return;
        }
        ServedSubtitle served = ServedSubtitles.of(edition, resolution.filePick());
        if (served != null) {
            due.add(new DueCheck(ref.id(), edition.getId(), ref.imdbId(), edition.getName(),
                OpenSubtitlesRanking.rankFor(filmCandidates, edition.getName()), tried, served.version(),
                served.ownerVerified(), served.cues()));
        }
    }

    private void addDownloadCheck(List<DueCheck> due, Edition edition, DownloadState download, OsBestRef ref,
                                  List<OpenSubtitlesResult> filmCandidates, Set<Long> tried) {
        SubtitleSidecar sidecar = download.sidecar();
        boolean waiting = download.alignment() == null && timingCheckEnabled;
        if (download.assessment().trust() != Trust.UNCHECKED || waiting) {
            return;
        }
        // A file cannot be the second opinion on itself.
        List<OpenSubtitlesResult> others = filmCandidates.stream()
            .filter(c -> c.fileId() != sidecar.getOpenSubtitlesFileId())
            .toList();
        ServedSubtitle served = others.isEmpty() ? null : ServedSubtitles.of(edition, Downloads.pick(sidecar));
        if (served != null) {
            due.add(new DueCheck(ref.id(), edition.getId(), ref.imdbId(), edition.getName(),
                OpenSubtitlesRanking.rankFor(others, edition.getName()), tried, served.version(), false,
                served.cues(), sidecar.getId()));
        }
    }

    /** The file id of a stored best result; 0 (no file) when it is not readable. Only films with a best result. */
    private static long bestFileId(String osBest) {
        OpenSubtitlesResult best = OpenSubtitlesResult.fromJson(osBest);
        return best == null ? 0 : best.fileId();
    }

    /** The single-file results in a stored candidates array. */
    private static List<OpenSubtitlesResult> singleFile(String json) {
        return OpenSubtitlesResult.listFromJson(json).stream().filter(OpenSubtitlesResult::isSingleFile).toList();
    }

    /**
     * Records that an edition has been compared with an OpenSubtitles file for an IMDb id (the ledger of the cap of
     * {@link ReferenceWalk#MAX_CANDIDATES}); nothing happens when it is already recorded.
     *
     * @param editionId the edition
     * @param imdbId the IMDb id the file was fetched for
     * @param osFileId the file id
     * @param now the time to stamp
     */
    @Transactional
    public void markTried(Long editionId, String imdbId, long osFileId, Instant now) {
        if (candidates.existsByEditionIdAndImdbIdAndOsFileId(editionId, imdbId, osFileId)) {
            return;
        }
        ForcedCheckCandidate row = new ForcedCheckCandidate();
        row.setEditionId(editionId);
        row.setImdbId(imdbId);
        row.setOsFileId(osFileId);
        row.setTriedAt(now);
        candidates.save(row);
    }

    /**
     * Saves a check, but only while it still describes what was compared: the film still has the IMDb id the reference
     * was fetched for, the reference is still one of the film's single-file candidates (and, for a download, is not the
     * download's own file), and what was checked still has exactly the version that was compared (an attach, move,
     * nudge, changed file or rescan meanwhile makes it stale: the result is dropped and it is due again). The owner's
     * files' check is the edition's one current row; a download's is its own row.
     *
     * @param due what was checked
     * @param reference the reference it was compared with
     * @param result the comparison
     * @param now the time to stamp
     * @return true when saved; false when it was dropped
     */
    @Transactional
    public boolean save(DueCheck due, OpenSubtitlesReference reference, SubtitleComparison.Result result,
                        Instant now) {
        Film film = films.findById(due.filmId()).orElse(null);
        if (film == null || !reference.getImdbId().equals(film.getImdbId())) {
            return false;
        }
        // The reference must still be one of the film's candidates (it is kept either way).
        boolean candidate = film.getOsCandidates() != null && film.getOsBest() != null
            && singleFile(film.getOsCandidates()).stream().anyMatch(c -> c.fileId() == reference.getOsFileId());
        if (!candidate) {
            return false;
        }
        Edition edition = editions.findWithFilesByFilmIdIn(List.of(due.filmId())).stream()
            .filter(e -> e.getId().equals(due.editionId())).findFirst().orElse(null);
        if (edition == null) {
            return false;
        }
        Resolution resolution = resolver.resolve(List.of(edition)).get(edition.getId());
        SubtitleCheckBase row;
        if (due.isDownload()) {
            DownloadState download = resolution.downloads().stream()
                .filter(d -> d.sidecar().getId().equals(due.downloadSidecarId())).findFirst().orElse(null);
            // A file is never the second opinion on itself.
            if (download == null || !due.servedVersion().equals(download.version())
                || download.sidecar().getOpenSubtitlesFileId() == reference.getOsFileId()) {
                return false;
            }
            ForcedDownloadCheck downloadRow = downloadChecks.findBySidecarId(download.sidecar().getId())
                .orElseGet(ForcedDownloadCheck::new);
            downloadRow.setSidecarId(download.sidecar().getId());
            row = downloadRow;
        } else {
            if (resolution.fileVersion() == null || !resolution.fileVersion().equals(due.servedVersion())) {
                return false;
            }
            ForcedSubtitleCheck fileRow = checks.findByEditionId(edition.getId()).orElseGet(ForcedSubtitleCheck::new);
            fileRow.setEditionId(edition.getId());
            row = fileRow;
        }
        row.setServedVersion(due.servedVersion());
        row.setReferenceId(reference.getId());
        row.setVerdict(result.verdict().name());
        row.setMatchedShare(result.matchedShare());
        row.setServedCount(result.servedCount());
        row.setReferenceCount(result.referenceCount());
        row.setBestOffsetMs(result.bestOffsetMs());
        row.setReasons(joined(result.reasons()));
        row.setRulesVersion(SubtitleCheckBase.RULES_VERSION);
        row.setCutTags(CutTag.key(due.editionName()));
        row.setBestFileId(bestFileId(film.getOsBest()));
        row.setCheckedAt(now);
        if (row instanceof ForcedDownloadCheck downloadRow) {
            downloadChecks.save(downloadRow);
        } else {
            checks.save((ForcedSubtitleCheck) row);
        }
        return true;
    }

    /** The reasons joined by newlines, never longer than the column (1,000 characters): whole reasons only. */
    static String joined(List<String> reasons) {
        StringBuilder out = new StringBuilder();
        for (String reason : reasons) {
            int needed = out.length() == 0 ? reason.length() : reason.length() + 1;
            if (out.length() + needed > MAX_REASONS_CHARS) {
                break;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(reason);
        }
        return out.toString();
    }
}
