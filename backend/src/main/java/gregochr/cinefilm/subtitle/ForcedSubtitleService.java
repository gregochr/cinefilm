package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.FilmDtos.ForcedSubtitleDto;
import gregochr.cinefilm.subtitle.ForcedChoice.Source;
import gregochr.cinefilm.subtitle.ForcedResolver.Resolution;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Pick;
import gregochr.cinefilm.subtitle.SidecarPrecedence.Slot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The forced English subtitle an edition plays when Subtitles is Off: which subtitle wins
 * ({@link ForcedChoice}, through {@link ForcedResolver}: only proven files are trusted), and its cues as one WebVTT
 * file on the edition's timeline (the offset applied; the files of a split edition's parts moved by each part's start
 * and joined). Built on each request: the files are small. Nothing from a request becomes a path: the cues come from
 * the database. When the winner is the film's own embedded forced track nothing is served from here (the player picks
 * the track itself).
 */
@Service
public class ForcedSubtitleService {

    private static final String SIDECAR = "SIDECAR";
    private static final String DOWNLOAD = "DOWNLOAD";

    private final EditionRepository editions;
    private final ForcedResolver resolver;

    /** Creates the service. */
    public ForcedSubtitleService(EditionRepository editions, ForcedResolver resolver) {
        this.editions = editions;
        this.resolver = resolver;
    }

    /**
     * The forced subtitle each of some editions plays, for the film detail.
     *
     * @param forEditions editions (their files must be loadable)
     * @return the subtitle by edition id; an edition with none is absent (so is one that plays its embedded track)
     */
    @Transactional(readOnly = true)
    public Map<Long, ForcedSubtitleDto> forEditions(Collection<Edition> forEditions) {
        Map<Long, ForcedSubtitleDto> byEdition = new HashMap<>();
        resolver.resolve(forEditions).forEach((editionId, resolution) -> {
            Pick pick = resolution.chosen();
            if (!pick.slots().isEmpty()) {
                boolean verified = pick.slots().stream().allMatch(s -> s.sidecar().isVerifiedFor(resolution.partIds()));
                String source = resolution.decision().source() == Source.DOWNLOAD ? DOWNLOAD : SIDECAR;
                long version = version(source, pick);
                byEdition.put(editionId, new ForcedSubtitleDto(source, verified, version));
            }
        });
        return byEdition;
    }

    /**
     * The cache-buster of the subtitle URL: a stable 48-bit hash of where the served rows come from and which they are
     * (ids, part, offset and last change), so switching between an owner's file and a download, a nudge, a changed file
     * or another stitch each give another number, and the same choice the same one. 48 bits stay exact as a JSON
     * number in JavaScript.
     */
    static long version(String source, Pick pick) {
        StringBuilder key = new StringBuilder(source);
        for (Slot slot : pick.slots()) {
            SubtitleSidecar s = slot.sidecar();
            key.append('|').append(s.getId()).append('@').append(slot.partIndex()).append('/').append(s.getOffsetMs())
                .append('/').append(s.getUpdatedAt().toEpochMilli());
        }
        byte[] digest = sha256().digest(key.toString().getBytes(StandardCharsets.UTF_8));
        long hash = 0;
        for (int i = 0; i < 6; i++) {
            hash = hash << 8 | (digest[i] & 0xFF);
        }
        return hash;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /**
     * The editions among some for which playback would show a forced subtitle: one served from here or the film's own
     * embedded forced track.
     *
     * @param forEditions editions (their files must be loadable)
     * @return the ids of the editions that show one
     */
    @Transactional(readOnly = true)
    public Set<Long> playableEditionIds(Collection<Edition> forEditions) {
        Set<Long> playable = new HashSet<>();
        resolver.resolve(forEditions).forEach((editionId, resolution) -> {
            if (resolution.decision().source() != Source.NONE) {
                playable.add(editionId);
            }
        });
        return playable;
    }

    /**
     * What each of some editions serves as cues on its timeline. The same choice as {@link #forEditions} and
     * {@link #vtt}; an edition with nothing served from here, or whose later part's start on the timeline is unknown,
     * is absent.
     *
     * @param forEditions editions (their files must be loadable)
     * @return the served subtitle by edition id
     */
    @Transactional(readOnly = true)
    public Map<Long, ServedSubtitle> served(Collection<Edition> forEditions) {
        Map<Long, ServedSubtitle> byEdition = new HashMap<>();
        resolver.resolve(forEditions).forEach((editionId, resolution) -> {
            Pick pick = resolution.chosen();
            ServedSubtitle served = pick.slots().isEmpty() ? null : ServedSubtitles.of(resolution.edition(), pick);
            if (served != null) {
                byEdition.put(editionId, served);
            }
        });
        return byEdition;
    }

    /**
     * The edition's forced subtitle as WebVTT on its timeline.
     *
     * @param editionId the edition
     * @return the WebVTT text
     * @throws NotFoundException when the edition is unknown, nothing plays for it from here, or its timeline is unknown
     */
    @Transactional(readOnly = true)
    public String vtt(Long editionId) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        Resolution resolution = resolver.resolve(List.of(edition)).get(edition.getId());
        List<Slot> slots = resolution.chosen().slots();
        if (slots.isEmpty()) {
            throw new NotFoundException("No forced subtitles");
        }
        List<String> texts = new ArrayList<>();
        EditionTimeline timeline = null;
        for (Slot slot : slots) {
            long start = 0;
            if (slot.partIndex() > 0) {
                timeline = timeline == null ? timeline(edition) : timeline;
                start = timeline.partStartMs(slot.partIndex());
            }
            // The part's start and the owner's offset move the cues together, so a cue shifted earlier than its
            // part's start is not clipped at the part's own 0 but lands on the edition timeline.
            texts.add(VttWriter.write(SrtParser.cues(slot.sidecar().getCuesText()),
                start + slot.sidecar().getOffsetMs()));
        }
        return texts.size() == 1 ? texts.get(0) : VttShifter.join(texts, Collections.nCopies(texts.size(), 0L));
    }

    private static EditionTimeline timeline(Edition edition) {
        try {
            return EditionTimeline.of(edition);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Forced subtitles are not available");
        }
    }
}
