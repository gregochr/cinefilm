package gregochr.cinefilm.transcode;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.TranscodeSession.Run;
import gregochr.cinefilm.transcode.TranscodeSession.Status;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns every live HLS session: one encoder run per session, started at the segment the player needs, killed when it
 * runs too far ahead or the player goes quiet, restarted when the player seeks somewhere it will not reach soon.
 * State changes happen under one lock; waiting for a segment happens outside it, on the caller's virtual thread.
 * Time comes from the injected clock, periodic upkeep from the injected scheduler, and file questions from
 * {@link SegmentFiles}, so the whole thing runs in tests without a process, a disk or a sleep.
 *
 * <p>A segment file is complete when the next segment's file exists (the encoder moved on) or when the run that
 * wrote it exited cleanly. A killed run's half-written segment is deleted at once, so that rule never mistakes it
 * for a finished one, and a restart first deletes every file at or after its first segment.
 */
@Component
public class SessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** The most sessions at once; a new one beyond it replaces the least recently used. */
    static final int MAX_SESSIONS = 3;
    /** A segment this many ahead of the encoder's newest is waited for; further than that restarts the encoder. */
    static final int WAIT_AHEAD_SEGMENTS = 10;
    /** The longest a segment request waits before answering 503. */
    static final Duration WAIT_MAX = Duration.ofSeconds(6);
    /** The encoder is killed when it is this far past the player. */
    static final Duration LOOK_AHEAD = Duration.ofMinutes(3);
    /** Segments further than this behind the player are deleted. */
    static final Duration BEHIND = Duration.ofMinutes(2);
    /** How often upkeep runs. */
    static final Duration SWEEP_PERIOD = Duration.ofSeconds(2);

    /** How many segments before the newest one a rung may lag behind the first rung when cleaning a killed run. */
    private static final int RUNG_LAG = 3;

    private final Transcoder transcoder;
    private final SegmentFiles files;
    private final SegmentWaiter waiter;
    private final SessionScheduler scheduler;
    private final Clock clock;
    private final SessionSettings settings;
    private final Path hlsRoot;
    private final Supplier<UUID> ids;
    private final Object lock = new Object();
    private final Map<String, TranscodeSession> sessions = new LinkedHashMap<>();
    private Runnable cancelSweep = () -> { };

    /** Creates the manager; sessions live under {@code <cinefilm.cache.dir>/hls}. */
    @Autowired
    SessionManager(Transcoder transcoder, SegmentFiles files, SegmentWaiter waiter, SessionScheduler scheduler,
                   Clock clock, SessionSettings settings, @Value("${cinefilm.cache.dir}") String cacheDir) {
        this(transcoder, files, waiter, scheduler, clock, settings,
            Path.of(cacheDir).toAbsolutePath().normalize().resolve("hls"), UUID::randomUUID);
    }

    SessionManager(Transcoder transcoder, SegmentFiles files, SegmentWaiter waiter, SessionScheduler scheduler,
                   Clock clock, SessionSettings settings, Path hlsRoot, Supplier<UUID> ids) {
        this.transcoder = transcoder;
        this.files = files;
        this.waiter = waiter;
        this.scheduler = scheduler;
        this.clock = clock;
        this.settings = settings;
        this.hlsRoot = hlsRoot;
        this.ids = ids;
    }

    /** Deletes what a previous run left in {@code hls/} and starts the periodic upkeep. */
    @PostConstruct
    void start() {
        cleanLeftovers();
        cancelSweep = scheduler.scheduleEvery(SWEEP_PERIOD, this::sweep);
    }

    /** Deletes every session folder under {@code hls/}: no session survives a restart. */
    void cleanLeftovers() {
        for (Path dir : files.directories(hlsRoot)) {
            files.deleteTree(dir);
        }
    }

    /** Stops the upkeep and every encoder when the application shuts down. */
    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            cancelSweep.run();
            for (TranscodeSession session : sessions.values()) {
                if (session.run.running()) {
                    kill(session.run);
                }
            }
        }
    }

    /**
     * Creates a session and starts its encoder at the first segment. A session for the same edition is replaced; at
     * the cap the least recently used one is.
     *
     * @param spec the validated session
     * @return the new session
     * @throws SessionStartException when the encoder cannot be started
     */
    public SessionView create(SessionSpec spec) {
        synchronized (lock) {
            for (TranscodeSession older : List.copyOf(sessions.values())) {
                if (older.spec.editionId() == spec.editionId()) {
                    destroy(older);
                }
            }
            while (sessions.size() >= MAX_SESSIONS) {
                destroy(sessions.values().stream().min(Comparator.comparing(s -> s.lastRequest)).orElseThrow());
            }
            String id = ids.get().toString();
            TranscodeSession session = new TranscodeSession(id, spec, hlsRoot.resolve(id), clock.instant());
            try {
                startRun(session, spec.startSegment());
            } catch (IOException e) {
                files.deleteTree(session.dir);
                throw new SessionStartException(e);
            }
            sessions.put(id, session);
            return view(session);
        }
    }

    /**
     * A session's description for the playlist endpoints; counts as a request.
     *
     * @param id the session id
     * @return the session
     * @throws NotFoundException when there is no such session (never created, ended or expired)
     */
    public SessionView view(String id) {
        synchronized (lock) {
            return view(require(id));
        }
    }

    /**
     * The file of a completed segment, waiting for it when the encoder will make it soon and restarting the encoder
     * when it will not.
     *
     * @param id the session id
     * @param rungId one of the session's rung ids
     * @param n the segment number
     * @return the segment file inside the session's folder
     * @throws NotFoundException when the session, rung or segment does not exist
     * @throws SegmentNotReadyException when it was not made within {@link #WAIT_MAX}
     * @throws SessionUnavailableException when the encoder failed twice
     */
    public Path segment(String id, String rungId, int n) {
        TranscodeSession session;
        synchronized (lock) {
            session = require(id);
            if (session.spec.rungs().stream().noneMatch(rung -> rung.id().equals(rungId))
                || n < 0 || n >= session.spec.cuts().segmentCount()) {
                throw new NotFoundException("No such segment");
            }
            session.lastRung = rungId;
            follow(session, n);
            if (complete(session, rungId, n)) {
                return session.segmentFile(rungId, n);
            }
            if (session.unavailable) {
                throw new SessionUnavailableException();
            }
            ensureReaching(session, n);
        }
        waiter.await(() -> settled(session, rungId, n), WAIT_MAX);
        synchronized (lock) {
            if (sessions.get(id) != session) {
                throw new NotFoundException("No such session");
            }
            if (complete(session, rungId, n)) {
                return session.segmentFile(rungId, n);
            }
            if (session.unavailable) {
                throw new SessionUnavailableException();
            }
            throw new SegmentNotReadyException();
        }
    }

    /** Ends a session: its encoder is stopped and its folder deleted. Ending an unknown session does nothing. */
    public void end(String id) {
        synchronized (lock) {
            TranscodeSession session = sessions.get(id);
            if (session != null) {
                destroy(session);
            }
        }
    }

    /** The live sessions, oldest first, for Admin. */
    public List<SessionDtos.SessionRow> sessions() {
        synchronized (lock) {
            return sessions.values().stream().map(this::info).toList();
        }
    }

    /** How many bytes {@code hls/} holds. */
    public long hlsBytes() {
        return files.size(hlsRoot);
    }

    /**
     * One round of upkeep: expire and idle-stop sessions, kill encoders that ran too far ahead, delete segments too
     * far behind the player, and evict idle sessions while {@code hls/} is over its cap.
     */
    void sweep() {
        synchronized (lock) {
            Instant now = clock.instant();
            for (TranscodeSession session : List.copyOf(sessions.values())) {
                maintain(session, now);
            }
        }
        long total = files.size(hlsRoot);
        synchronized (lock) {
            evictIdle(total);
        }
    }

    private void maintain(TranscodeSession session, Instant now) {
        Duration idle = Duration.between(session.lastRequest, now);
        if (idle.compareTo(settings.expiry()) >= 0) {
            destroy(session);
            return;
        }
        Run run = session.run;
        if (run.running()) {
            advance(session, run);
            if (idle.compareTo(settings.idleStop()) >= 0 || tooFarAhead(session, run)) {
                stop(session, run);
            }
        }
        deleteBehind(session);
    }

    private void evictIdle(long total) {
        Instant now = clock.instant();
        long remaining = total;
        List<TranscodeSession> idle = sessions.values().stream()
            .filter(s -> Duration.between(s.lastRequest, now).compareTo(settings.idleStop()) >= 0)
            .sorted(Comparator.comparing(s -> s.lastRequest))
            .toList();
        for (TranscodeSession session : idle) {
            if (remaining <= settings.hlsCapBytes()) {
                return;
            }
            remaining -= files.size(session.dir);
            destroy(session);
        }
    }

    private SessionView view(TranscodeSession session) {
        SessionSpec spec = session.spec;
        return new SessionView(session.id, spec.rungs(), spec.startRung(), spec.pinned(), spec.cuts(), spec.width(),
            spec.height());
    }

    private SessionDtos.SessionRow info(TranscodeSession session) {
        Run run = session.run;
        Double speed = null;
        int ahead = 0;
        if (run.running()) {
            advance(session, run);
            speed = run.speed > 0 ? run.speed : null;
            ahead = Math.max(0, run.frontier - session.playhead);
        }
        return new SessionDtos.SessionRow(session.id, session.spec.filmTitle(), session.spec.editionId(),
            session.lastRung, speed, ahead, session.startedAt, session.unavailable);
    }

    private TranscodeSession require(String id) {
        TranscodeSession session = sessions.get(id);
        if (session == null) {
            throw new NotFoundException("No such session");
        }
        session.lastRequest = clock.instant();
        return session;
    }

    /** Moves the playhead to a requested segment: forward always, back only by more than {@link #BEHIND}. */
    private void follow(TranscodeSession session, int n) {
        CutList cuts = session.spec.cuts();
        long behind = cuts.startMs(session.playhead) - cuts.startMs(n);
        if (behind != Math.clamp(behind, 0L, BEHIND.toMillis())) {
            session.playhead = n;
        }
    }

    private boolean complete(TranscodeSession session, String rungId, int n) {
        if (!files.exists(session.segmentFile(rungId, n))) {
            return false;
        }
        if (n + 1 < session.spec.cuts().segmentCount() && files.exists(session.segmentFile(rungId, n + 1))) {
            return true;
        }
        Run run = session.run;
        return run.status == Status.FINISHED && n >= run.startSegment;
    }

    private boolean settled(TranscodeSession session, String rungId, int n) {
        synchronized (lock) {
            return sessions.get(session.id) != session || session.unavailable || !session.run.running()
                || complete(session, rungId, n);
        }
    }

    /** Leaves a live encoder that will reach the segment soon alone; otherwise restarts it at the segment. */
    private void ensureReaching(TranscodeSession session, int n) {
        Run run = session.run;
        if (run.running()) {
            advance(session, run);
            if (n >= run.startSegment && n <= run.frontier + WAIT_AHEAD_SEGMENTS) {
                return;
            }
        }
        stop(session, run);
        session.playhead = n;
        try {
            startRun(session, n);
        } catch (IOException e) {
            LOG.warn("Cannot restart the encoder for session {}: {}", session.id, e.getMessage());
            session.unavailable = true;
            throw new SessionUnavailableException();
        }
    }

    private void startRun(TranscodeSession session, int segment) throws IOException {
        SessionSpec spec = session.spec;
        for (Rung rung : spec.rungs()) {
            files.deleteFrom(session.dir.resolve(rung.id()), segment);
        }
        Run run = new Run(segment);
        TranscodePlan plan = new TranscodePlan(spec.source(), session.dir, spec.rungs(), spec.audioIndex(),
            spec.cuts(), segment, transcoder.mode(), settings.threads(), spec.fps());
        run.process = transcoder.start(plan, new RunListener(session, run));
        session.run = run;
        session.deletedBelow = Math.min(session.deletedBelow, segment);
    }

    /** Moves the run's newest-segment mark forward over the files the first rung has written. */
    private void advance(TranscodeSession session, Run run) {
        String first = session.spec.rungs().get(0).id();
        int last = session.spec.cuts().segmentCount() - 1;
        while (run.frontier < last && files.exists(session.segmentFile(first, run.frontier + 1))) {
            run.frontier++;
        }
    }

    private boolean tooFarAhead(TranscodeSession session, Run run) {
        CutList cuts = session.spec.cuts();
        return cuts.startMs(run.frontier) - cuts.startMs(session.playhead) > LOOK_AHEAD.toMillis();
    }

    private void kill(Run run) {
        run.status = Status.STOPPED;
        run.process.stop();
    }

    /** Stops a running encoder and deletes the segment it was in the middle of. */
    private void stop(TranscodeSession session, Run run) {
        if (run.running()) {
            kill(run);
            cleanPartial(session, run);
        }
    }

    /** Deletes, in every rung, the last segment file of a dead run: it is half written. */
    private void cleanPartial(TranscodeSession session, Run run) {
        advance(session, run);
        int last = session.spec.cuts().segmentCount() - 1;
        for (Rung rung : session.spec.rungs()) {
            int n = Math.max(run.startSegment, run.frontier - RUNG_LAG);
            while (n < last && files.exists(session.segmentFile(rung.id(), n + 1))) {
                n++;
            }
            files.delete(session.segmentFile(rung.id(), n));
        }
    }

    private void deleteBehind(TranscodeSession session) {
        CutList cuts = session.spec.cuts();
        long limit = cuts.startMs(session.playhead) - BEHIND.toMillis();
        int below = cuts.segmentAt(Math.max(0, limit));
        for (int n = session.deletedBelow; n < below; n++) {
            for (Rung rung : session.spec.rungs()) {
                files.delete(session.segmentFile(rung.id(), n));
            }
        }
        session.deletedBelow = Math.max(session.deletedBelow, below);
    }

    private void destroy(TranscodeSession session) {
        if (session.run.running()) {
            kill(session.run);
        }
        files.deleteTree(session.dir);
        sessions.remove(session.id);
    }

    private void exited(TranscodeSession session, Run run, int exitCode, String logTail) {
        if (run.status == Status.STOPPED) {
            return;
        }
        if (exitCode == 0) {
            run.status = Status.FINISHED;
            return;
        }
        LOG.warn("ffmpeg for session {} exited with {}: {}", session.id, exitCode, logTail);
        run.status = Status.CRASHED;
        cleanPartial(session, run);
        session.crashes++;
        if (session.crashes > 1) {
            session.unavailable = true;
            return;
        }
        try {
            startRun(session, run.frontier);
        } catch (IOException e) {
            LOG.warn("Cannot restart the encoder for session {}: {}", session.id, e.getMessage());
            session.unavailable = true;
        }
    }

    /** Receives one run's progress and exit; a run the manager already stopped is ignored. */
    private final class RunListener implements EncoderListener {

        private final TranscodeSession session;
        private final Run run;

        RunListener(TranscodeSession session, Run run) {
            this.session = session;
            this.run = run;
        }

        @Override
        public void onProgress(EncoderProgress progress) {
            synchronized (lock) {
                if (!run.running()) {
                    return;
                }
                run.speed = progress.speed();
                advance(session, run);
                if (tooFarAhead(session, run)) {
                    stop(session, run);
                }
            }
        }

        @Override
        public void onExit(int exitCode, String logTail) {
            synchronized (lock) {
                exited(session, run, exitCode, logTail);
            }
        }
    }
}
