package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.FilmDtos.ForcedSubtitleDto;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The forced English subtitle an edition plays when Subtitles is Off: which sidecar files win
 * ({@link SidecarPrecedence}), and their cues as one WebVTT file on the edition's timeline (the owner's offset applied;
 * the files of a split edition's parts moved by each part's start and joined). Built on each request: the files are
 * small. Nothing from a request becomes a path: the cues come from the database.
 */
@Service
public class ForcedSubtitleService {

    private static final String SIDECAR = "SIDECAR";

    private final SubtitleSidecarRepository sidecars;
    private final EditionRepository editions;

    /** Creates the service. */
    public ForcedSubtitleService(SubtitleSidecarRepository sidecars, EditionRepository editions) {
        this.sidecars = sidecars;
        this.editions = editions;
    }

    /**
     * The forced subtitle each of some editions plays, for the film detail.
     *
     * @param forEditions editions (their files must be loadable)
     * @return the subtitle by edition id; an edition with none is absent
     */
    @Transactional(readOnly = true)
    public Map<Long, ForcedSubtitleDto> forEditions(Collection<Edition> forEditions) {
        List<Long> allIds = forEditions.stream().flatMap(e -> partIds(e).stream()).toList();
        List<SubtitleSidecar> found = allIds.isEmpty() ? List.of() : sidecars.findByMediaFileIdIn(allIds);
        Map<Long, ForcedSubtitleDto> byEdition = new HashMap<>();
        for (Edition edition : forEditions) {
            List<Long> partIds = partIds(edition);
            Pick pick = SidecarPrecedence.pick(partIds, found);
            if (!pick.slots().isEmpty()) {
                boolean verified = pick.slots().stream().allMatch(s -> s.sidecar().isVerifiedFor(partIds));
                long version = pick.slots().stream().mapToLong(s -> s.sidecar().getUpdatedAt().toEpochMilli())
                    .max().orElseThrow();
                byEdition.put(edition.getId(), new ForcedSubtitleDto(SIDECAR, verified, version));
            }
        }
        return byEdition;
    }

    /**
     * The edition's forced subtitle as WebVTT on its timeline.
     *
     * @param editionId the edition
     * @return the WebVTT text
     * @throws NotFoundException when the edition is unknown, nothing plays for it, or its timeline is unknown
     */
    @Transactional(readOnly = true)
    public String vtt(Long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        List<Long> partIds = partIds(edition);
        List<Slot> slots = partIds.isEmpty() ? List.of()
            : SidecarPrecedence.pick(partIds, sidecars.findByMediaFileIdIn(partIds)).slots();
        if (slots.isEmpty()) {
            throw new NotFoundException("No forced subtitles");
        }
        List<String> texts = new ArrayList<>();
        List<Long> starts = new ArrayList<>();
        if (slots.get(0).partIndex() > 0) {
            texts.add("WEBVTT\n");
            starts.add(0L);
        }
        EditionTimeline timeline = null;
        for (Slot slot : slots) {
            long start = 0;
            if (slot.partIndex() > 0) {
                timeline = timeline == null ? timeline(edition) : timeline;
                start = timeline.partStartMs(slot.partIndex());
            }
            texts.add(VttWriter.write(SrtParser.cues(slot.sidecar().getCuesText()), slot.sidecar().getOffsetMs()));
            starts.add(start);
        }
        return texts.size() == 1 ? texts.get(0) : VttShifter.join(texts, starts);
    }

    private static EditionTimeline timeline(Edition edition) {
        try {
            return EditionTimeline.of(edition);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Forced subtitles are not available");
        }
    }

    private static List<Long> partIds(Edition edition) {
        return edition.originalParts().stream().map(MediaFile::getId).toList();
    }
}
