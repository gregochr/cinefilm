package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.FilmDtos.FullSubtitleDto;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The full English subtitle an edition has, found and served (the player menu is phase 5c): the owner's
 * {@code X.en.srt} files beside the
 * film (kind {@link SubtitleKind#FULL}), picked with {@link SidecarPrecedence}'s rules and served as one WebVTT file on
 * the edition's timeline like the forced subtitle ({@link ServedSubtitles#vtt}). There is no choice to make between
 * sources yet: only the owner's files serve (downloads of full subtitles come in a later phase), and nothing here ever
 * reads a forced row. Built on each request; nothing from a request becomes a path.
 */
@Service
public class FullSubtitleService {

    private static final String SIDECAR = "SIDECAR";

    private final EditionRepository editions;
    private final SubtitleSidecarRepository sidecars;

    /** Creates the service. */
    public FullSubtitleService(EditionRepository editions, SubtitleSidecarRepository sidecars) {
        this.editions = editions;
        this.sidecars = sidecars;
    }

    /**
     * The full subtitle each of some editions offers, for the film detail.
     *
     * @param forEditions editions (their files must be loadable)
     * @return the subtitle by edition id; an edition with none (or a conflict, or an unknown timeline) is absent
     */
    @Transactional(readOnly = true)
    public Map<Long, FullSubtitleDto> forEditions(Collection<Edition> forEditions) {
        Map<Long, FullSubtitleDto> byEdition = new HashMap<>();
        Map<Long, Pick> picks = picks(forEditions);
        for (Edition edition : forEditions) {
            Pick pick = picks.get(edition.getId());
            if (!pick.slots().isEmpty() && ServedSubtitles.version(edition, pick) != null) {
                long version = ForcedSubtitleService.version(SIDECAR, pick);
                byEdition.put(edition.getId(), new FullSubtitleDto(SIDECAR, version));
            }
        }
        return byEdition;
    }

    /**
     * The edition's full subtitle as WebVTT on its timeline.
     *
     * @param editionId the edition
     * @return the WebVTT text
     * @throws NotFoundException when the edition is unknown, no full file plays for it, or its timeline is unknown
     */
    @Transactional(readOnly = true)
    public String vtt(Long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        Pick pick = picks(List.of(edition)).get(edition.getId());
        if (pick.slots().isEmpty()) {
            throw new NotFoundException("No full subtitles");
        }
        return ServedSubtitles.vtt(edition, pick, "Full subtitles are not available");
    }

    /** What {@link SidecarPrecedence} picks from the full files of each edition, in one query for all of them. */
    private Map<Long, Pick> picks(Collection<Edition> forEditions) {
        List<Long> allIds = forEditions.stream().flatMap(e -> partIds(e).stream()).toList();
        List<SubtitleSidecar> found = allIds.isEmpty() ? List.of()
            : sidecars.findByMediaFileIdInAndKind(allIds, SubtitleKind.FULL);
        Map<Long, Pick> picks = new HashMap<>();
        for (Edition edition : forEditions) {
            picks.put(edition.getId(),SidecarPrecedence.pick(partIds(edition), found, SubtitleKind.FULL));
        }
        return picks;
    }

    private static List<Long> partIds(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }
}
