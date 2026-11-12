package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.opensubtitles.CutTag;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of checking the owner's forced subtitle files against OpenSubtitles: which editions are due, and
 * saving a result. Kept apart from {@link SidecarCheckRefresher} (which talks to OpenSubtitles) so each can be tested
 * on its own.
 */
@Service
public class SidecarCheckService {

    private final FilmRepository films;
    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;
    private final ForcedSubtitleService forced;
    private final ForcedSubtitleCheckRepository checks;
    private final OpenSubtitlesReferenceRepository references;
    private final ForcedCheckCandidateRepository candidates;

    /** The longest reasons text the column holds. */
    static final int MAX_REASONS_CHARS = 1000;

    /** Creates the service. */
    public SidecarCheckService(FilmRepository films, SubtitleSidecarRepository sidecars, EditionRepository editions,
                               ForcedSubtitleService forced, ForcedSubtitleCheckRepository checks,
                               OpenSubtitlesReferenceRepository references,
                               ForcedCheckCandidateRepository candidates) {
        this.films = films;
        this.sidecars = sidecars;
        this.editions = editions;
        this.forced = forced;
        this.checks = checks;
        this.references = references;
        this.candidates = candidates;
    }

    /**
     * The editions to check: the film has results from a search of its current IMDb id that also stored its
     * candidates (a film searched before they were kept waits for the search to run again), playback serves a forced
     * subtitle for the edition (the same pick as playback), and the edition has no check that still describes what is
     * served (made by the current rules, same served version, a reference for the film's current IMDb id that is still
     * one of the film's candidates). The owner's unverified files come first, then the verified ones (still worth
     * checking for completeness); then by film and edition. Each due edition carries the candidates in the order to
     * try them for its own cut ({@link OpenSubtitlesRanking#rankFor}) and the files it has already been compared with.
     *
     * @return the due editions in the order to check them
     */
    @Transactional(readOnly = true)
    public List<DueCheck> due() {
        Map<Long, OsBestRef> best = new HashMap<>();
        for (OsBestRef ref : films.findOpenSubtitlesBest()) {
            best.put(ref.id(), ref);
        }
        Set<Long> filmIds = new HashSet<>(sidecars.findFilmIdsWithPresentSidecar());
        filmIds.retainAll(best.keySet());
        if (filmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(filmIds);
        Map<Long, ServedSubtitle> served = forced.served(fetched);
        if (served.isEmpty()) {
            return List.of();
        }
        Map<Long, ForcedSubtitleCheck> checkByEdition = new HashMap<>();
        checks.findByEditionIdIn(served.keySet()).forEach(c -> checkByEdition.put(c.getEditionId(), c));
        Map<Long, OpenSubtitlesReference> referenceById = new HashMap<>();
        references.findAllById(checkByEdition.values().stream().map(ForcedSubtitleCheck::getReferenceId).toList())
            .forEach(r -> referenceById.put(r.getId(), r));
        List<ForcedCheckCandidate> ledger = candidates.findByEditionIdIn(served.keySet());
        List<DueCheck> due = new ArrayList<>();
        for (Edition edition : fetched) {
            ServedSubtitle subtitle = served.get(edition.getId());
            OsBestRef ref = best.get(edition.getFilm().getId());
            // A film searched before its candidates were kept is not checked until the search has written them.
            if (subtitle == null || ref.osCandidates() == null) {
                continue;
            }
            List<OpenSubtitlesResult> filmCandidates = singleFile(ref.osCandidates());
            Set<Long> candidateIds = new HashSet<>();
            filmCandidates.forEach(c -> candidateIds.add(c.fileId()));
            ForcedSubtitleCheck current = checkByEdition.get(edition.getId());
            if (current != null && current.isCurrent(subtitle.version(), ref.imdbId(),
                referenceById.get(current.getReferenceId()), bestFileId(ref.osBest()))
                && candidateIds.contains(referenceById.get(current.getReferenceId()).getOsFileId())
                && current.getCutTags().equals(CutTag.key(edition.getName()))) {
                continue;
            }
            Set<Long> tried = new HashSet<>();
            ledger.stream().filter(l -> l.getEditionId().equals(edition.getId()) && l.getImdbId().equals(ref.imdbId()))
                .forEach(l -> tried.add(l.getOsFileId()));
            due.add(new DueCheck(ref.id(), edition.getId(), ref.imdbId(), edition.getName(),
                OpenSubtitlesRanking.rankFor(filmCandidates, edition.getName()), Set.copyOf(tried),
                subtitle.version(), subtitle.ownerVerified(), subtitle.cues()));
        }
        due.sort(Comparator.comparing(DueCheck::ownerVerified).thenComparing(DueCheck::filmId)
            .thenComparing(DueCheck::editionId));
        return due;
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
     * Saves a check as the edition's one current row, but only while it still describes the edition: the film still
     * has the IMDb id the reference was fetched for, the reference is still one of the film's single-file candidates,
     * and the edition still serves exactly the version that was compared (an attach, move, nudge, changed file or
     * rescan meanwhile makes it stale: the result is dropped and the edition is due again).
     *
     * @param due the edition and what was compared
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
        ServedSubtitle served = forced.served(List.of(edition)).get(edition.getId());
        if (served == null || !served.version().equals(due.servedVersion())) {
            return false;
        }
        ForcedSubtitleCheck row = checks.findByEditionId(edition.getId()).orElseGet(ForcedSubtitleCheck::new);
        row.setEditionId(edition.getId());
        row.setServedVersion(due.servedVersion());
        row.setReferenceId(reference.getId());
        row.setVerdict(result.verdict().name());
        row.setMatchedShare(result.matchedShare());
        row.setServedCount(result.servedCount());
        row.setReferenceCount(result.referenceCount());
        row.setBestOffsetMs(result.bestOffsetMs());
        row.setReasons(joined(result.reasons()));
        row.setRulesVersion(ForcedSubtitleCheck.RULES_VERSION);
        row.setCutTags(CutTag.key(due.editionName()));
        row.setBestFileId(bestFileId(film.getOsBest()));
        row.setCheckedAt(now);
        checks.save(row);
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
