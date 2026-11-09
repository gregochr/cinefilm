package gregochr.cinefilm.transcode;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionDtos.RenditionDto;
import gregochr.cinefilm.transcode.SessionDtos.SessionCreated;
import gregochr.cinefilm.transcode.SessionSources.AudioChoice;
import gregochr.cinefilm.transcode.SessionSources.Source;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The playback-session API's rules: validates every request value (bodies against the database, path variables
 * against fixed patterns), builds the ladder, and hands the session manager a fully validated spec. Nothing from a
 * request is ever turned into a file path except a pattern-checked rung id and segment number under the session's
 * own folder.
 */
@Service
public class SessionService {

    private static final Pattern ID = Pattern.compile("^[0-9a-f-]{36}$");
    private static final Pattern RUNG = Pattern.compile("^(original|1080p|720p|480p|360p|240p)$");
    private static final Pattern SEGMENT = Pattern.compile("^seg-(\\d{1,6})\\.ts$");

    private final SessionSources sources;
    private final KeyframeIndexService indexes;
    private final Transcoder transcoder;
    private final SessionManager manager;

    /** Creates the service. */
    public SessionService(SessionSources sources, KeyframeIndexService indexes, Transcoder transcoder,
                          SessionManager manager) {
        this.sources = sources;
        this.indexes = indexes;
        this.transcoder = transcoder;
        this.manager = manager;
    }

    /**
     * Creates a playback session for an edition.
     *
     * @param editionId the edition
     * @param request the request body
     * @return the session
     * @throws NotFoundException when the edition has no playable original
     * @throws BadRequestException when the quality, audio stream, start position or probe is invalid
     * @throws ConflictException when the film cannot be streamed adaptively (the client direct-plays instead)
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
        AudioChoice audio = chooseAudio(source, request.audioStreamId());
        MediaFile file = source.file();
        if (file.getDurationMs() == null || file.getDurationMs() <= 0 || file.getHeight() == null
            || file.getHeight() < 2) {
            throw new ConflictException("The film's duration or size is unknown");
        }
        long startMs = request.startMs() == null ? 0 : request.startMs();
        if (startMs < 0 || startMs >= file.getDurationMs()) {
            throw new BadRequestException("startMs is outside the film");
        }
        EncoderMode mode = transcoder.mode();
        if (mode == EncoderMode.UNAVAILABLE) {
            throw new ConflictException("Transcoding is unavailable");
        }
        KeyframeIndexService.Index index = index(file);
        long bitrate = file.getBitrate() == null ? 0 : file.getBitrate();
        boolean hardwareDecode = SourceFormat.isH264Yuv420(file.getVideoCodec(), file.getPixelFormat());
        List<Rung> ladder = Ladder.rungs(file.getHeight(), bitrate, index.cuts().copyOk() && hardwareDecode, mode);
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
        int width = file.getWidth() == null ? file.getHeight() * 16 / 9 / 2 * 2 : file.getWidth();
        SessionSpec spec = new SessionSpec(editionId, source.filmTitle(), source.path(), audio.position(),
            index.cuts(), index.fps(), width, file.getHeight(), encoded, start, pinned,
            index.cuts().segmentAt(startMs), hardwareDecode);
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

    private static AudioChoice chooseAudio(Source source, Long requested) {
        List<AudioChoice> audio = source.audio();
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
