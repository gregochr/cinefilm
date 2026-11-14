package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which films still have an automatic subtitle download to come: the facts {@code AdminTodoRules} judges "being
 * checked" on. It answers from the same code the download pipeline uses ({@link DownloadPlan},
 * {@link ForcedDownloadService}'s due tests) over editions, downloads and blocks fetched together, so the cost does
 * not grow with the number of films. The daily limit is deliberately not looked at: a download that is due but deferred
 * is still to come.
 */
@Service
public class PendingDownloads {

    /**
     * What the pipeline still has for some films.
     *
     * @param withCandidate films that have a stored candidate that can still become a download (full only)
     * @param withDueEdition films that have a present edition due a download with room under the cap
     */
    public record Pending(Set<Long> withCandidate, Set<Long> withDueEdition) {

        /** Nothing pending. */
        public static final Pending NONE = new Pending(Set.of(), Set.of());
    }

    private final FilmRepository films;
    private final EditionRepository editions;
    private final SubtitleSidecarRepository sidecars;
    private final ForcedResolver resolver;
    private final OpenSubtitlesReferenceRepository references;
    private final ForcedDownloadBlockRepository blocks;

    /** Creates the service. */
    public PendingDownloads(FilmRepository films, EditionRepository editions, SubtitleSidecarRepository sidecars,
                            ForcedResolver resolver, OpenSubtitlesReferenceRepository references,
                            ForcedDownloadBlockRepository blocks) {
        this.films = films;
        this.editions = editions;
        this.sidecars = sidecars;
        this.resolver = resolver;
        this.references = references;
        this.blocks = blocks;
    }

    /**
     * For films searched for full candidates under their current IMDb id: which still have a candidate that can become
     * a download (not spent: {@link DownloadPlan#toFetch}, whose spent test is {@link DownloadPlan#promotable}), and
     * which have a present edition that is {@link DownloadPlan#fullDue} with room under
     * {@value DownloadPlan#MAX_DOWNLOADS}.
     *
     * @param imdbIds the films to ask about, by id, with their current IMDb id
     * @return the films with a usable candidate, and the films with a due edition with room
     */
    @Transactional(readOnly = true)
    public Pending full(Map<Long, String> imdbIds) {
        if (imdbIds.isEmpty()) {
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
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> stored = storedByFilm(candidates.keySet());
        Set<Long> withCandidate = new HashSet<>();
        candidates.forEach((id, ref) -> {
            List<OpenSubtitlesResult> single = OpenSubtitlesResult.listFromJson(ref.osFullCandidates()).stream()
                .filter(OpenSubtitlesResult::isSingleFile).toList();
            Map<Long, OpenSubtitlesReference> byFile = stored.getOrDefault(id, Map.of())
                .getOrDefault(ref.imdbId(), Map.of());
            if (!DownloadPlan.toFetch(single, Set.of(), Set.of(), byFile, SubtitleKind.FULL).isEmpty()) {
                withCandidate.add(id);
            }
        });
        List<Edition> fetched = presentEditions(candidates.keySet());
        List<Long> editionIds = fetched.stream().map(Edition::getId).toList();
        List<SubtitleSidecar> allFull = sidecars.findByMediaFileIdInAndKind(partIds(fetched), SubtitleKind.FULL);
        List<SubtitleSidecar> downloads = allFull.stream().filter(SubtitleSidecar::isDownload).toList();
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdInAndKind(editionIds, SubtitleKind.FULL);
        Set<Long> withDue = new HashSet<>();
        for (Edition edition : fetched) {
            Long filmId = edition.getFilm().getId();
            String imdbId = candidates.get(filmId).imdbId();
            if (DownloadPlan.fullDue(ForcedDownloadService.hasOwnerFile(edition, allFull),
                ForcedDownloadService.hasDownload(edition, allFull))
                && DownloadPlan.remaining(ForcedDownloadService.skippedFileIds(edition, imdbId, SubtitleKind.FULL,
                    allBlocks, downloads)) > 0) {
                withDue.add(filmId);
            }
        }
        return new Pending(withCandidate, withDue);
    }

    /**
     * Which of these films (whose need is shown, with no forced subtitle playing, and that OpenSubtitles had forced
     * files for) have a present edition that is due a forced download ({@link DownloadPlan#kind}) with room under
     * {@value DownloadPlan#MAX_DOWNLOADS}.
     *
     * @param imdbIds the films to ask about, by id, with their current IMDb id
     * @return the films with a due edition with room ({@code withCandidate} is empty)
     */
    @Transactional(readOnly = true)
    public Pending forced(Map<Long, String> imdbIds) {
        if (imdbIds.isEmpty()) {
            return Pending.NONE;
        }
        List<Edition> fetched = presentEditions(imdbIds.keySet());
        Map<Long, Resolution> resolved = resolver.resolve(fetched);
        List<Long> editionIds = fetched.stream().map(Edition::getId).toList();
        List<ForcedDownloadBlock> allBlocks = blocks.findByEditionIdInAndKind(editionIds, SubtitleKind.FORCED);
        List<SubtitleSidecar> downloads = sidecars.findByMediaFileIdInAndKind(partIds(fetched), SubtitleKind.FORCED)
            .stream().filter(SubtitleSidecar::isDownload).toList();
        Set<Long> withDue = new HashSet<>();
        for (Edition edition : fetched) {
            Long filmId = edition.getFilm().getId();
            if (ForcedDownloadService.kind(resolved.get(edition.getId())).isPresent()
                && DownloadPlan.remaining(ForcedDownloadService.skippedFileIds(edition, imdbIds.get(filmId),
                    SubtitleKind.FORCED, allBlocks, downloads)) > 0) {
                withDue.add(filmId);
            }
        }
        return new Pending(Set.of(), withDue);
    }

    /** The films' editions whose part 1 is on disk, with their files. */
    private List<Edition> presentEditions(Collection<Long> filmIds) {
        return editions.findWithFilesByFilmIdIn(filmIds).stream().filter(e -> {
            MediaFile part1 = e.originalFile();
            return part1 != null && part1.getPresence() == Presence.PRESENT;
        }).toList();
    }

    private static List<Long> partIds(List<Edition> fetched) {
        return fetched.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList();
    }

    private Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm(Set<Long> filmIds) {
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> byFilm = new HashMap<>();
        references.findByFilmIdIn(filmIds).forEach(r -> byFilm
            .computeIfAbsent(r.getFilmId(), k -> new HashMap<>())
            .computeIfAbsent(r.getImdbId(), k -> new HashMap<>()).put(r.getOsFileId(), r));
        return byFilm;
    }
}
