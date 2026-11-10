package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.film.SubtitleStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The subtitles of a whole edition as one WebVTT file on the edition's timeline. The viewer picks a subtitle stream
 * of part 1 (the logical stream); each part's matching stream is extracted by {@link SubtitleService} (which caches
 * it), the cues of later parts are moved by the part's start, and the parts are joined. A film in one piece is served
 * exactly as its file's extraction. Nothing from the request becomes a path: only a stream index that part 1 has.
 */
@Service
public class EditionSubtitleService {

    private final EditionRepository editions;
    private final SubtitleService subtitles;
    private final TransactionTemplate transactions;

    /** Creates the service. */
    public EditionSubtitleService(EditionRepository editions, SubtitleService subtitles,
                                  TransactionTemplate transactions) {
        this.editions = editions;
        this.subtitles = subtitles;
        this.transactions = transactions;
    }

    /** One part's stream to extract and where that part starts on the edition's timeline. */
    private record Target(Long mediaFileId, int streamIndex, long startMs) {
    }

    /**
     * The WebVTT of one of the edition's subtitle streams.
     *
     * @param editionId the edition
     * @param streamIndex the stream index in part 1 (as stored, the ffprobe index)
     * @return the WebVTT text
     * @throws NotFoundException when the edition or stream is unknown, a part's file is gone or extraction fails
     */
    public String vtt(Long editionId, int streamIndex) {
        List<Target> targets = transactions.execute(status -> targets(editionId, streamIndex));
        List<String> texts = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        for (Target target : targets) {
            Path file = subtitles.vtt(target.mediaFileId(), target.streamIndex());
            try {
                texts.add(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new NotFoundException("Subtitles could not be extracted");
            }
            offsets.add(target.startMs());
        }
        return texts.size() == 1 ? texts.get(0) : VttShifter.join(texts, offsets);
    }

    private List<Target> targets(Long editionId, int streamIndex) {
        Edition edition = editions.findById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
        List<MediaFile> parts = edition.originalParts();
        if (parts.isEmpty()) {
            throw new NotFoundException("Edition has no original file");
        }
        List<SubtitleStream> firstStreams = parts.get(0).getSubtitleStreams();
        SubtitleStream chosen = firstStreams.stream().filter(s -> s.getIndex() == streamIndex).findFirst()
            .orElseThrow(() -> new NotFoundException("No such subtitle stream"));
        List<Target> targets = new ArrayList<>();
        targets.add(new Target(parts.get(0).getId(), streamIndex, 0));
        if (parts.size() == 1) {
            return targets;
        }
        EditionTimeline timeline;
        try {
            timeline = EditionTimeline.of(edition);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Subtitles are not available");
        }
        for (int i = 1; i < parts.size(); i++) {
            Optional<SubtitleStream> match = PartSubtitles.match(chosen, firstStreams,
                parts.get(i).getSubtitleStreams());
            if (match.isPresent()) {
                targets.add(new Target(parts.get(i).getId(), match.get().getIndex(), timeline.partStartMs(i)));
            }
        }
        return targets;
    }
}
