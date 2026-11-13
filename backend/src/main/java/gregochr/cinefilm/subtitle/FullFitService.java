package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.credits.CreditsDetection;
import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the full-subtitle measurement ({@link FullFitProbe}, phase 5d-1): which full downloads are
 * worth measuring, with the facts that go in the log line. Nothing is stored and nothing here changes what plays.
 */
@Service
public class FullFitService {

    private final FilmRepository films;
    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;
    private final OpenSubtitlesReferenceRepository references;
    private final CreditsDetectionRepository credits;

    /** Creates the service. */
    public FullFitService(FilmRepository films, SubtitleSidecarRepository sidecars, EditionRepository editions,
                          OpenSubtitlesReferenceRepository references, CreditsDetectionRepository credits) {
        this.films = films;
        this.sidecars = sidecars;
        this.editions = editions;
        this.references = references;
        this.credits = credits;
    }

    /**
     * One full download to measure.
     *
     * @param filmId the film
     * @param editionId the edition
     * @param imdbId the film's IMDb id the download and the candidates are for
     * @param sidecarId the download's row
     * @param downloadFileId the download's OpenSubtitles file id
     * @param download the download's cues
     * @param runtimeMs the edition's runtime, 0 when unknown
     * @param creditsStartMs where credits detection found the credits start on the edition's timeline, or null
     * @param others the film's other single-file full candidates, in the order for this edition's cut (the download's
     *     own file is never among them)
     * @param stored the film's stored references for this IMDb id by file id
     */
    record Job(Long filmId, Long editionId, String imdbId, Long sidecarId, long downloadFileId,
               List<SubtitleCue> download, long runtimeMs, Long creditsStartMs, List<OpenSubtitlesResult> others,
               Map<Long, OpenSubtitlesReference> stored) {
    }

    /**
     * The live full downloads that have not been measured yet, for films that still have full candidates from a
     * search of their current IMDb id.
     *
     * @param measured the ids of the download rows already measured by this process
     * @return the jobs, by film and edition
     */
    @Transactional(readOnly = true)
    public List<Job> due(Set<Long> measured) {
        Map<Long, OsFullRef> candidates = new HashMap<>();
        for (OsFullRef ref : films.findOpenSubtitlesFull()) {
            candidates.put(ref.id(), ref);
        }
        Set<Long> filmIds = new HashSet<>(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FULL));
        filmIds.retainAll(candidates.keySet());
        if (filmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> fetched = editions.findWithFilesByFilmIdIn(filmIds);
        List<Long> allParts = fetched.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList();
        List<SubtitleSidecar> found = sidecars.findByMediaFileIdInAndKind(allParts, SubtitleKind.FULL);
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm = new HashMap<>();
        references.findByFilmIdIn(filmIds).forEach(r -> storedByFilm
            .computeIfAbsent(r.getFilmId(), k -> new HashMap<>())
            .computeIfAbsent(r.getImdbId(), k -> new HashMap<>()).put(r.getOsFileId(), r));
        List<Job> jobs = new ArrayList<>();
        for (Edition edition : fetched) {
            OsFullRef ref = candidates.get(edition.getFilm().getId());
            List<Long> partIds = edition.originalParts().stream().map(MediaFile::getId).toList();
            for (SubtitleSidecar download : Downloads.of(edition, partIds, found, SubtitleKind.FULL)) {
                if (!measured.contains(download.getId())) {
                    jobs.add(job(edition, ref, download, storedByFilm.getOrDefault(ref.id(), Map.of())
                        .getOrDefault(ref.imdbId(), Map.of())));
                }
            }
        }
        return jobs;
    }

    private Job job(Edition edition, OsFullRef ref, SubtitleSidecar download,
                    Map<Long, OpenSubtitlesReference> stored) {
        List<OpenSubtitlesResult> others = OpenSubtitlesRanking.rankForFull(
            OpenSubtitlesResult.listFromJson(ref.osFullCandidates()).stream()
                .filter(c -> c.isSingleFile() && c.fileId() != download.getOpenSubtitlesFileId()).toList(),
            edition.getName());
        return new Job(ref.id(), edition.getId(), ref.imdbId(), download.getId(), download.getOpenSubtitlesFileId(),
            SrtParser.cues(download.getCuesText()), edition.getRuntimeMs(), creditsStart(edition), others, stored);
    }

    /** Where credits detection found the credits start on the edition's timeline; null when it found none. */
    private Long creditsStart(Edition edition) {
        List<MediaFile> parts = edition.originalParts();
        int last = parts.size() - 1;
        CreditsDetection detection = credits.findById(parts.get(last).getId()).orElse(null);
        if (detection == null || detection.getVerdict() != CreditsVerdict.FOUND || detection.getOffsetMs() == null) {
            return null;
        }
        if (last == 0) {
            return detection.getOffsetMs();
        }
        try {
            return EditionTimeline.of(edition).partStartMs(last) + detection.getOffsetMs();
        } catch (IllegalArgumentException e) {
            // A part's length is not known: the credits start cannot be placed on the edition's timeline.
            return null;
        }
    }
}
