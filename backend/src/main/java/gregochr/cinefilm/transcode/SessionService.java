package gregochr.cinefilm.transcode;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionDtos.RenditionDto;
import gregochr.cinefilm.transcode.SessionDtos.SessionCreated;
import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import gregochr.cinefilm.transcode.SessionSources.Part;
import gregochr.cinefilm.transcode.SessionSources.Source;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The playback-session API's rules: validates every request value (bodies against the database, path variables
 * against fixed patterns), builds the ladder, and hands the session manager a fully validated spec. Nothing from a
 * request is ever turned into a file path except a pattern-checked rung id and segment number under the session's
 * own folder.
 */
@Service
public class SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    /** How long a request for a split film waits for its later parts to be indexed before answering 503. */
    static final Duration INDEX_WAIT = Duration.ofSeconds(8);

    private static final Pattern ID = Pattern.compile("^[0-9a-f-]{36}$");
    private static final Pattern RUNG = Pattern.compile("^(original|1080p|720p|480p|360p|240p)$");
    private static final Pattern SEGMENT = Pattern.compile("^seg-(\\d{1,6})\\.ts$");

    private final SessionSources sources;
    private final KeyframeIndexService indexes;
    private final Transcoder transcoder;
    private final SessionManager manager;
    private final Executor indexExecutor;
    private final Duration indexWait;

    /** Creates the service; the later parts of a split film are indexed on the {@code sessionIndexExecutor}. */
    @Autowired
    public SessionService(SessionSources sources, KeyframeIndexService indexes, Transcoder transcoder,
                          SessionManager manager, @Qualifier("sessionIndexExecutor") Executor indexExecutor) {
        this(sources, indexes, transcoder, manager, indexExecutor, INDEX_WAIT);
    }

    /** A service that indexes later parts on the calling thread (a film in one piece never needs the executor). */
    public SessionService(SessionSources sources, KeyframeIndexService indexes, Transcoder transcoder,
                          SessionManager manager) {
        this(sources, indexes, transcoder, manager, Runnable::run, INDEX_WAIT);
    }

    SessionService(SessionSources sources, KeyframeIndexService indexes, Transcoder transcoder,
                   SessionManager manager, Executor indexExecutor, Duration indexWait) {
        this.sources = sources;
        this.indexes = indexes;
        this.transcoder = transcoder;
        this.manager = manager;
        this.indexExecutor = indexExecutor;
        this.indexWait = indexWait;
    }

    /**
     * Creates a playback session for an edition.
     *
     * @param editionId the edition
     * @param request the request body
     * @return the session
     * @throws NotFoundException when the edition has no playable original
     * @throws BadRequestException when the quality, audio stream, start position or probe is invalid
     * @throws ConflictException when the film cannot be streamed adaptively (the client direct-plays instead; a split
     *     film cannot be direct-played, so for it the client shows that it cannot be played)
     * @throws SegmentNotReadyException when a split film's later parts are still being indexed (retry shortly)
     */
    public SessionCreated create(long editionId, CreateRequest request) {
        Source source = sources.find(editionId);
        String quality = request.quality();
        if (quality == null || !Ladder.QUALITY_IDS.contains(quality)) {
            throw new BadRequestException("Invalid quality");
        }
        if (request.probeKbps() != null && request.probeKbps() < 0) {
            throw new BadRequestException("Invalid probe");
        }
        List<Part> parts = source.parts();
        AudioChoice audio = chooseAudio(parts.get(0).audio(), request.audioStreamId());
        for (Part part : parts) {
            MediaFile partFile = part.file();
            if (part.timelineMs() <= 0 || partFile.getHeight() == null || partFile.getHeight() < 2) {
                throw new ConflictException("The film's duration or size is unknown");
            }
        }
        long totalMs = parts.stream().mapToLong(Part::timelineMs).sum();
        long startMs = request.startMs() == null ? 0 : request.startMs();
        if (startMs < 0 || startMs >= totalMs) {
            throw new BadRequestException("startMs is outside the film");
        }
        EncoderMode mode = transcoder.mode();
        if (mode == EncoderMode.UNAVAILABLE) {
            throw new ConflictException("Transcoding is unavailable");
        }
        List<KeyframeIndexService.Index> indexed = parts.size() == 1
            ? List.of(index(parts.get(0).file())) : indexParts(parts);
        CutList cuts = parts.size() == 1 ? indexed.get(0).cuts()
            : CutList.join(indexed.stream().map(KeyframeIndexService.Index::cuts).toList());
        // The picture is the smallest part's (the parts of one film are normally the same size); the original is
        // copied only when the parts match, so a film whose parts differ is encoded throughout.
        MediaFile smallest = parts.stream().map(Part::file).min(Comparator.comparingInt(MediaFile::getHeight))
            .orElseThrow();
        MediaFile file = parts.get(0).file();
        long bitrate = file.getBitrate() == null ? 0 : file.getBitrate();
        boolean hardwareDecode = parts.stream().allMatch(
            part -> SourceFormat.isH264Yuv420(part.file().getVideoCodec(), part.file().getPixelFormat()));
        List<Rung> ladder = Ladder.forSource(smallest.getHeight(), bitrate,
            source.partsMatch() ? file.getVideoCodec() : null, file.getPixelFormat(), () -> cuts, mode);
        String pinned = Ladder.AUTO.equals(quality) ? null : quality;
        List<Rung> encoded = ladder;
        Rung start;
        if (pinned == null) {
            start = StartRung.pick(ladder, request.probeKbps());
        } else {
            encoded = ladder.stream().filter(rung -> rung.id().equals(pinned)).toList();
            if (encoded.isEmpty()) {
                throw pinned.equals(Ladder.ORIGINAL)
                    ? new ConflictException("The original cannot be copied for this film")
                    : new BadRequestException("Quality not available for this film");
            }
            start = encoded.get(0);
        }
        int width = smallest.getWidth() == null ? smallest.getHeight() * 16 / 9 / 2 * 2 : smallest.getWidth();
        List<SessionSpec.PartSource> partSources = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            AudioChoice choice = i == 0 ? audio : partAudio(audio, parts.get(i), i);
            partSources.add(new SessionSpec.PartSource(parts.get(i).path(), choice.position(), indexed.get(i).fps()));
        }
        SessionSpec spec = new SessionSpec(editionId, source.filmTitle(), partSources, cuts, width,
            smallest.getHeight(), encoded, start, pinned, cuts.segmentAt(startMs), hardwareDecode);
        SessionView created;
        try {
            created = manager.create(spec);
        } catch (SessionStartException e) {
            throw new ConflictException("Transcoding could not start");
        }
        return new SessionCreated(created.id(), "/api/sessions/" + created.id() + "/master.m3u8", start.id(),
            ladder.stream().map(r -> new RenditionDto(r.id(), r.height(), r.videoBitrate(), r.copy())).toList());
    }

    /** The master playlist of a session. */
    public String master(String id) {
        SessionView view = manager.view(sessionId(id));
        return PlaylistWriter.master(view.rungs(), view.startRung(), view.pinned(), view.width(), view.height());
    }

    /** The media playlist of one rung of a session. */
    public String media(String id, String rung) {
        SessionView view = manager.view(sessionId(id));
        if (!RUNG.matcher(rung).matches() || view.rungs().stream().noneMatch(r -> r.id().equals(rung))) {
            throw new NotFoundException("No such rung");
        }
        return PlaylistWriter.media(view.cuts());
    }

    /** The file of a segment, once it is complete (see {@link SessionManager#segment}). */
    public Path segment(String id, String rung, String file) {
        String sessionId = sessionId(id);
        Matcher matcher = SEGMENT.matcher(file);
        if (!RUNG.matcher(rung).matches() || !matcher.matches()) {
            throw new NotFoundException("No such segment");
        }
        return manager.segment(sessionId, rung, Integer.parseInt(matcher.group(1)));
    }

    /** Ends a session; ending one that is already gone is fine. */
    public void end(String id) {
        manager.end(sessionId(id));
    }

    private static String sessionId(String id) {
        if (!ID.matcher(id).matches()) {
            throw new NotFoundException("No such session");
        }
        return id;
    }

    private KeyframeIndexService.Index index(MediaFile file) {
        try {
            return indexes.ensureIndexed(file);
        } catch (KeyframeIndexException e) {
            throw new ConflictException("This film cannot be streamed adaptively");
        }
    }

    /**
     * The indexes of a split film's parts, each ending where its video ends. Part 1 is indexed now; the later parts
     * are usually indexed already (the index job runs after every scan), and any that are not are indexed in the
     * background for a limited wait, after which the request answers 503 and the client asks again (the work carries
     * on meanwhile).
     */
    private List<KeyframeIndexService.Index> indexParts(List<Part> parts) {
        List<KeyframeIndexService.Index> found = new ArrayList<>();
        try {
            found.add(indexes.ensureIndexed(parts.get(0).file(), parts.get(0).timelineMs()));
        } catch (KeyframeIndexException e) {
            throw new ConflictException("This film cannot be streamed adaptively");
        }
        List<CompletableFuture<KeyframeIndexService.Index>> later = new ArrayList<>();
        for (Part part : parts.subList(1, parts.size())) {
            Optional<KeyframeIndexService.Index> cached = indexes.cached(part.file(), part.timelineMs());
            later.add(cached.map(CompletableFuture::completedFuture).orElseGet(() -> CompletableFuture.supplyAsync(
                () -> indexes.ensureIndexed(part.file(), part.timelineMs()), indexExecutor)));
        }
        try {
            CompletableFuture.allOf(later.toArray(CompletableFuture[]::new)).get(indexWait.toMillis(),
                TimeUnit.MILLISECONDS);
            for (CompletableFuture<KeyframeIndexService.Index> done : later) {
                found.add(done.get());
            }
        } catch (TimeoutException e) {
            throw new SegmentNotReadyException("The film is still being prepared");
        } catch (ExecutionException e) {
            LOG.warn("A part of a split film could not be indexed: {}", e.getCause().getMessage());
            throw new ConflictException("This film cannot be streamed adaptively");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SegmentNotReadyException("The film is still being prepared");
        }
        return found;
    }

    private static AudioChoice partAudio(AudioChoice chosen, Part part, int index) {
        if (part.audio().isEmpty()) {
            throw new ConflictException("Part " + (index + 1) + " has no audio stream");
        }
        return PartAudio.match(chosen, part.audio());
    }

    private static AudioChoice chooseAudio(List<AudioChoice> audio, Long requested) {
        if (audio.isEmpty()) {
            throw new ConflictException("The file has no audio stream");
        }
        if (requested != null) {
            return audio.stream().filter(a -> a.id() == requested.longValue()).findFirst()
                .orElseThrow(() -> new BadRequestException("No such audio stream"));
        }
        return audio.stream().filter(AudioChoice::isDefault).findFirst().orElse(audio.get(0));
    }
}
