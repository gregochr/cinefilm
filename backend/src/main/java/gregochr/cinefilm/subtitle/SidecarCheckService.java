package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
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

    /** Creates the service. */
    public SidecarCheckService(FilmRepository films, SubtitleSidecarRepository sidecars, EditionRepository editions,
                               ForcedSubtitleService forced, ForcedSubtitleCheckRepository checks,
                               OpenSubtitlesReferenceRepository references) {
        this.films = films;
        this.sidecars = sidecars;
        this.editions = editions;
        this.forced = forced;
        this.checks = checks;
        this.references = references;
    }

    /**
     * The editions to check: the film has a best OpenSubtitles result from a search of its current IMDb id, playback
     * serves a forced subtitle for the edition (the same pick as playback), and the edition has no check that still
     * describes what is served (same served version, and a reference for the film's current IMDb id and best
     * file). The owner's unverified files come first, then the verified ones (still worth checking for
     * completeness); then by film and edition.
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
        List<DueCheck> due = new ArrayList<>();
        for (Edition edition : fetched) {
            ServedSubtitle subtitle = served.get(edition.getId());
            OsBestRef ref = best.get(edition.getFilm().getId());
            OpenSubtitlesResult result = subtitle == null ? null : OpenSubtitlesResult.fromJson(ref.osBest());
            if (result == null) {
                continue;
            }
            ForcedSubtitleCheck current = checkByEdition.get(edition.getId());
            if (current != null && current.isCurrent(subtitle.version(), ref.imdbId(),
                referenceById.get(current.getReferenceId()), result.fileId())) {
                continue;
            }
            due.add(new DueCheck(ref.id(), edition.getId(), ref.imdbId(), result.fileId(), result.release(),
                result.fileCount(), subtitle.version(), subtitle.ownerVerified(), subtitle.cues()));
        }
        due.sort(Comparator.comparing(DueCheck::ownerVerified).thenComparing(DueCheck::filmId)
            .thenComparing(DueCheck::editionId));
        return due;
    }

    /**
     * Saves a check as the edition's one current row, but only while it still describes the edition: the film still
     * has the IMDb id the reference was fetched for, and the edition still serves exactly the version that was
     * compared (an attach, move, nudge, changed file or rescan meanwhile makes it stale: the result is dropped and the
     * edition is due again).
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
        // The film's best file must still be the one that was compared with (the reference is kept either way).
        OpenSubtitlesResult best = film.getOsBest() == null ? null : OpenSubtitlesResult.fromJson(film.getOsBest());
        if (best == null || best.fileId() != due.osFileId()) {
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
        row.setReasons(String.join("\n", result.reasons()));
        row.setCheckedAt(now);
        checks.save(row);
        return true;
    }
}
