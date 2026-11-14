package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsBestRef;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which films the download scheduler would download something for: the fact {@code AdminTodoRules} calls "a download is
 * due". It is the scheduler's own decision, not an approximation of it: for each edition it runs
 * {@link ForcedDownloadService#fullDueFor} or {@link ForcedDownloadService#dueFor}, the same pure functions
 * {@link ForcedDownloadService#due()} runs, over the same facts (the film's candidates, the edition's blocks, its
 * ledger, its downloads and the stored references) loaded together for all the films asked about, so the cost does not
 * grow with the number of films.
 *
 * <p>The editions are the scheduler's editions ({@code findWithFilesByFilmIdIn}, no presence filter), so an edition
 * whose files are missing counts exactly as it does for the scheduler. The daily limit is deliberately not looked at: a
 * download that is due but deferred is still to come. With downloads off (no API key, username or password) the
 * scheduler never downloads, so nothing is pending and nothing is read.
 */
@Service
public class PendingDownloads {

    private final FilmRepository films;
    private final EditionRepository editions;
    private final SubtitleSidecarRepository sidecars;
    private final ForcedResolver resolver;
    private final OpenSubtitlesReferenceRepository references;
    private final ForcedDownloadBlockRepository blocks;
    private final ForcedCheckCandidateRepository ledger;
    private final OpenSubtitlesApi api;

    /** Creates the service. */
    public PendingDownloads(FilmRepository films, EditionRepository editions, SubtitleSidecarRepository sidecars,
                            ForcedResolver resolver, OpenSubtitlesReferenceRepository references,
                            ForcedDownloadBlockRepository blocks, ForcedCheckCandidateRepository ledger,
                            OpenSubtitlesApi api) {
        this.films = films;
        this.editions = editions;
        this.sidecars = sidecars;
        this.resolver = resolver;
        this.references = references;
        this.blocks = blocks;
        this.ledger = ledger;
        this.api = api;
    }

    /**
     * What the scheduler would do for some films.
     *
     * @param due the films for which some edition would be given a download
     */
    public record Pending(Set<Long> due) {

        /** Nothing pending. */
        public static final Pending NONE = new Pending(Set.of());
    }

    /**
     * Of these films, those the scheduler would download a full English subtitle for: some edition would come back from
     * {@link ForcedDownloadService#fullDueFor} (due, under the cap, and with a candidate left after blocks, the ledger
     * and spent references).
     *
     * @param imdbIds the films to ask about (shown, no full subtitle playing, searched), by id
     * @return the films with a full download to come
     */
    @Transactional(readOnly = true)
    public Pending full(Map<Long, String> imdbIds) {
        if (imdbIds.isEmpty() || !api.downloadsEnabled()) {
            return Pending.NONE;
        }
        Map<Long, OsFullRef> candidates = new HashMap<>();
        for (OsFullRef ref : films.findOpenSubtitlesFull()) {
            if (imdbIds.containsKey(ref.id())) {
                candidates.put(ref.id(), ref);
            }
        }
        if (candidates.isEmpty()) {
            return Pending.NONE;
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(candidates.keySet());
        List<Long> editionIds = fetched.stream().map(Edition::getId).toList();
        List<Long> partIds = fetched.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList();
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdInAndKind(editionIds, SubtitleKind.FULL);
        List<ForcedCheckCandidate> allLedger = ledger.findByEditionIdInAndKind(editionIds, SubtitleKind.FULL);
        List<SubtitleSidecar> allFull = sidecars.findByMediaFileIdInAndKind(partIds, SubtitleKind.FULL);
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> stored = storedByFilm(candidates.keySet());
        Set<Long> due = new HashSet<>();
        for (Edition edition : fetched) {
            OsFullRef ref = candidates.get(edition.getFilm().getId());
            if (ForcedDownloadService.fullDueFor(edition, ref, allBlocks, allLedger, allFull,
                stored.getOrDefault(ref.id(), Map.of()).getOrDefault(ref.imdbId(), Map.of())) != null) {
                due.add(ref.id());
            }
        }
        return new Pending(due);
    }

    /**
     * Of these films, those the scheduler would download a forced subtitle for: they have candidates from a search of
     * their current IMDb id, and some edition would come back from {@link ForcedDownloadService#dueFor} (due, a
     * single-file candidate or a promotion left after blocks, the ledger and spent references, under the cap).
     *
     * @param imdbIds the films to ask about (shown, no forced subtitle playing, files found online), by id
     * @return the films with a forced download to come
     */
    @Transactional(readOnly = true)
    public Pending forced(Map<Long, String> imdbIds) {
        if (imdbIds.isEmpty() || !api.downloadsEnabled()) {
            return Pending.NONE;
        }
        Map<Long, OsBestRef> best = new HashMap<>();
        for (OsBestRef ref : films.findOpenSubtitlesBest()) {
            if (imdbIds.containsKey(ref.id()) && ref.osCandidates() != null) {
                best.put(ref.id(), ref);
            }
        }
        if (best.isEmpty()) {
            return Pending.NONE;
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(best.keySet());
        Map<Long, Resolution> resolved = resolver.resolve(fetched);
        List<Long> editionIds = fetched.stream().map(Edition::getId).toList();
        List<Long> partIds = fetched.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList();
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdInAndKind(editionIds, SubtitleKind.FORCED);
        List<ForcedCheckCandidate> allLedger = ledger.findByEditionIdInAndKind(editionIds, SubtitleKind.FORCED);
        List<SubtitleSidecar> downloads = sidecars.findByMediaFileIdInAndKind(partIds, SubtitleKind.FORCED).stream()
            .filter(SubtitleSidecar::isDownload).toList();
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> stored = storedByFilm(best.keySet());
        Set<Long> due = new HashSet<>();
        for (Edition edition : fetched) {
            OsBestRef ref = best.get(edition.getFilm().getId());
            if (ForcedDownloadService.dueFor(edition, resolved.get(edition.getId()), ref, allBlocks, allLedger,
                downloads, stored.getOrDefault(ref.id(), Map.of()).getOrDefault(ref.imdbId(), Map.of())) != null) {
                due.add(ref.id());
            }
        }
        return new Pending(due);
    }

    private Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm(Set<Long> filmIds) {
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> byFilm = new HashMap<>();
        references.findByFilmIdIn(filmIds).forEach(r -> byFilm
            .computeIfAbsent(r.getFilmId(), k -> new HashMap<>())
            .computeIfAbsent(r.getImdbId(), k -> new HashMap<>()).put(r.getOsFileId(), r));
        return byFilm;
    }
}
