package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.credits.CreditsDetectionRepository;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.OsFullRef;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedResolver.DownloadState;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import gregochr.cinefilm.subtitle.TimingReferenceRule.FileFacts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of phase 3c-1: which editions get a full English timing reference
 * ({@link TimingReferenceRule#qualifies}) and what the fetch and the measurement need to know about each. Read only.
 * It does nothing while the timing check is off ({@code cinefilm.subtitles.timing-check.enabled=false}): the editions'
 * embedded tracks are not read then, so none can qualify.
 */
@Service
public class TimingReferenceService {

    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;
    private final EmbeddedCueStartsRepository reads;
    private final ForcedResolver resolver;
    private final FilmRepository films;
    private final OpenSubtitlesReferenceRepository references;
    private final CreditsDetectionRepository credits;
    private final boolean enabled;

    /** Creates the service. */
    public TimingReferenceService(SubtitleSidecarRepository sidecars, EditionRepository editions,
                                  EmbeddedCueStartsRepository reads, ForcedResolver resolver, FilmRepository films,
                                  OpenSubtitlesReferenceRepository references, CreditsDetectionRepository credits,
                                  @Value("${cinefilm.subtitles.timing-check.enabled:true}") boolean enabled) {
        this.sidecars = sidecars;
        this.editions = editions;
        this.reads = reads;
        this.resolver = resolver;
        this.films = films;
        this.references = references;
        this.credits = credits;
        this.enabled = enabled;
    }

    /** Where a forced file came from: the owner's file, a download, or a download the owner picked. */
    enum Origin { OWNER, DOWNLOAD, CHOSEN }

    /**
     * A forced file to measure.
     *
     * @param sidecarId its row
     * @param origin where it came from
     * @param lines its cue count
     * @param cuesText its cues as stored (the raw file: no owner offset)
     */
    record File(Long sidecarId, Origin origin, int lines, String cuesText) {
    }

    /**
     * An edition with a timing reference to find.
     *
     * @param filmId the film
     * @param editionId the edition
     * @param editionName the edition's name (its cut tags order the candidates)
     * @param imdbId the film's current IMDb id, which the full candidates were searched for
     * @param runtimeMs the edition's runtime
     * @param creditsStartMs where credits detection found the credits start on the edition's timeline, or null
     * @param ownerPick true when the owner picked one of its files
     * @param files its forced files
     * @param candidates the film's single-file full candidates with a plausible frame rate, in the order for this
     *     edition's cut (a hearing-impaired file last)
     * @param referencesKept how many of the film's stored references are among its current full candidates
     * @param stored the film's stored references for this IMDb id by OpenSubtitles file id
     */
    record Target(Long filmId, Long editionId, String editionName, String imdbId, long runtimeMs, Long creditsStartMs,
                  boolean ownerPick, List<File> files, List<OpenSubtitlesResult> candidates, int referencesKept,
                  Map<Long, OpenSubtitlesReference> stored) {
    }

    /** An edition that qualifies, before the film's candidates are looked at. */
    private record Qualified(Edition edition, List<File> files, boolean ownerPick) {
    }

    /**
     * The films with an edition that qualifies: they are searched for full English subtitles as well.
     *
     * @return the film ids (those with an IMDb id), empty while the timing check is off
     */
    @Transactional(readOnly = true)
    public Set<Long> filmIds() {
        return qualified().stream().map(q -> q.edition().getFilm().getId()).collect(Collectors.toSet());
    }

    /**
     * The editions to find and measure a reference for, owner's picks first, then by film and edition: those whose
     * film has full candidates from a search of its current IMDb id.
     *
     * @return the targets
     */
    @Transactional(readOnly = true)
    public List<Target> targets() {
        List<Qualified> found = qualified();
        if (found.isEmpty()) {
            return List.of();
        }
        Map<Long, OsFullRef> candidates = new HashMap<>();
        films.findOpenSubtitlesFull().forEach(ref -> candidates.put(ref.id(), ref));
        Set<Long> filmIds = found.stream().map(q -> q.edition().getFilm().getId()).collect(Collectors.toSet());
        filmIds.retainAll(candidates.keySet());
        if (filmIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Map<Long, OpenSubtitlesReference>>> storedByFilm = new HashMap<>();
        references.findByFilmIdIn(filmIds).forEach(r -> storedByFilm
            .computeIfAbsent(r.getFilmId(), k -> new HashMap<>())
            .computeIfAbsent(r.getImdbId(), k -> new HashMap<>()).put(r.getOsFileId(), r));
        List<Target> targets = new ArrayList<>();
        for (Qualified q : found) {
            OsFullRef ref = candidates.get(q.edition().getFilm().getId());
            if (ref != null) {
                targets.add(target(q, ref, storedByFilm.getOrDefault(ref.id(), Map.of())
                    .getOrDefault(ref.imdbId(), Map.of())));
            }
        }
        return targets;
    }

    private Target target(Qualified q, OsFullRef ref, Map<Long, OpenSubtitlesReference> stored) {
        Edition edition = q.edition();
        List<OpenSubtitlesResult> all = OpenSubtitlesResult.listFromJson(ref.osFullCandidates());
        Set<Long> currentIds = all.stream().map(OpenSubtitlesResult::fileId).collect(Collectors.toSet());
        int kept = (int) stored.keySet().stream().filter(currentIds::contains).count();
        List<OpenSubtitlesResult> ranked = OpenSubtitlesRanking.rankForFull(all.stream()
            .filter(c -> c.isSingleFile() && TimingReferenceRule.plausibleFps(c)).toList(), edition.getName());
        return new Target(ref.id(), edition.getId(), edition.getName(), ref.imdbId(), edition.getRuntimeMs(),
            CreditsStarts.of(edition, credits), q.ownerPick(), q.files(), ranked, kept, stored);
    }

    /** The editions that qualify, owner's picks first, then by film and edition; empty while the check is off. */
    private List<Qualified> qualified() {
        if (!enabled) {
            return List.of();
        }
        Set<Long> filmIds = new HashSet<>(sidecars.findFilmIdsWithPresentSubtitle(SubtitleKind.FORCED));
        if (filmIds.isEmpty()) {
            return List.of();
        }
        List<Edition> found = editions.findWithFilesByFilmIdIn(filmIds).stream()
            .filter(e -> e.getRuntimeMs() > 0 && e.getFilm().getImdbId() != null && !e.originalParts().isEmpty())
            .toList();
        Map<Long, EmbeddedCueStarts> readByFile = new HashMap<>();
        reads.findAllById(found.stream().flatMap(e -> e.originalParts().stream()).map(MediaFile::getId).toList())
            .forEach(r -> readByFile.put(r.getMediaFileId(), r));
        List<Edition> withoutTrack = found.stream().filter(e -> noReference(e, readByFile)).toList();
        if (withoutTrack.isEmpty()) {
            return List.of();
        }
        Map<Long, Resolution> resolved = resolver.resolve(withoutTrack);
        List<Qualified> qualified = new ArrayList<>();
        for (Edition edition : withoutTrack) {
            Resolution resolution = resolved.get(edition.getId());
            List<SubtitleSidecar> served = new ArrayList<>(resolution.filePick().slots().stream()
                .map(Slot::sidecar).toList());
            served.addAll(resolution.downloads().stream().map(DownloadState::sidecar).toList());
            List<FileFacts> facts = served.stream().map(s -> new FileFacts(s.getCueCount(),
                s.isVerifiedFor(resolution.partIds()), s.isWholeEdition())).toList();
            if (TimingReferenceRule.qualifies(facts, resolution.decision().source() == Source.EMBEDDED,
                edition.getRuntimeMs(), edition.originalParts().size())) {
                List<File> files = served.stream().map(s -> new File(s.getId(), origin(s), s.getCueCount(),
                    s.getCuesText())).toList();
                qualified.add(new Qualified(edition, files, files.stream().anyMatch(f -> f.origin() == Origin.CHOSEN)));
            }
        }
        qualified.sort(Comparator.comparing((Qualified q) -> !q.ownerPick())
            .thenComparing(q -> q.edition().getFilm().getId()).thenComparing(q -> q.edition().getId()));
        return qualified;
    }

    private static Origin origin(SubtitleSidecar sidecar) {
        return !sidecar.isDownload() ? Origin.OWNER : sidecar.isOwnerChosen() ? Origin.CHOSEN : Origin.DOWNLOAD;
    }

    /** True when every part is present and its embedded track was read as it is now and found no English track. */
    private static boolean noReference(Edition edition, Map<Long, EmbeddedCueStarts> readByFile) {
        return edition.originalParts().stream().allMatch(part -> {
            EmbeddedCueStarts read = readByFile.get(part.getId());
            String fingerprint = TimingKeys.fingerprint(part);
            return part.getPresence() == Presence.PRESENT && fingerprint != null && read != null
                && fingerprint.equals(read.getFingerprint())
                && read.getStatus() == EmbeddedCueStarts.Status.NO_REFERENCE;
        });
    }
}
