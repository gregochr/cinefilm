package gregochr.cinefilm.transcode;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.transcode.TranscodeSession.Run;
import gregochr.cinefilm.transcode.TranscodeSession.Status;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
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
 * A film split across several files is encoded one file at a time: a run covers one part, and when it ends cleanly
 * the next run starts at the first segment of the next part (unless the player is far behind), so the player sees one
 * continuous film. A restart or a clean-up touches only the run's own part.
 * A session that went quiet (no request for the expiry, or idle while {@code hls/} is over its cap) is deleted but
 * remembered: its spec, its files' size and modification time and a little bookkeeping stay in memory, and its next
 * request rebuilds it under the same id (see {@link #revive}), so a paused film's player keeps its playlists and its
 * buffer. Every other ending (ended, replaced, pushed out at the cap, unavailable) is final.
 * State changes happen under one lock; waiting for a segment happens outside it, on the caller's virtual thread.
 * Time comes from the injected clock, periodic upkeep from the injected scheduler, and file questions from
 * {@link SegmentFiles}, so the whole thing runs in tests without a process, a disk or a sleep.
 *
 * <p>A segment file is complete when the next segment's file exists (the encoder moved on) or, for the last segment
 * of a part, when a run of that part exited cleanly. A killed run's half-written segment is deleted at once, so that
 * rule never mistakes it for a finished one, and a restart first deletes every file of its part at or after its first
 * segment.
 */
@Component
public class SessionManager {

    private static final Logger LOG = LoggerFactory.getLogger(SessionManager.class);

    /** The most sessions at once; a new one beyond it replaces the least recently used. */
    static final int MAX_SESSIONS = 3;
    /** A segment this many ahead of the encoder's newest is waited for; further than that restarts the encoder. */
    static final int WAIT_AHEAD_SEGMENTS = 10;
    /** The most quiet sessions remembered for rebuilding; the eldest is forgotten beyond it. */
    static final int MAX_REMEMBERED = 8;
    /** A remembered session is never rebuilt this long or longer after its last request. */
    static final Duration REMEMBER_FOR = Duration.ofHours(24);
    /** The longest a segment request waits (counted from its arrival) before answering 503. */
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
    /** The quiet sessions that can be rebuilt, eldest first; guarded by {@link #lock}. */
    private final Map<String, Remembered> remembered = new LinkedHashMap<>();
    private final FileStamps stamps;
    private Runnable cancelSweep = () -> { };

    /** Creates the manager; sessions live under {@code <cinefilm.cache.dir>/hls}. */
    @Autowired
    SessionManager(Transcoder transcoder, SegmentFiles files, SegmentWaiter waiter, SessionScheduler scheduler,
                   Clock clock, SessionSettings settings, FileStamps stamps,
                   @Value("${cinefilm.cache.dir}") String cacheDir) {
        this(transcoder, files, waiter, scheduler, clock, settings, stamps,
            Path.of(cacheDir).toAbsolutePath().normalize().resolve("hls"), UUID::randomUUID);
    }

    SessionManager(Transcoder transcoder, SegmentFiles files, SegmentWaiter waiter, SessionScheduler scheduler,
                   Clock clock, SessionSettings settings, FileStamps stamps, Path hlsRoot, Supplier<UUID> ids) {
        this.transcoder = transcoder;
        this.files = files;
        this.waiter = waiter;
        this.scheduler = scheduler;
        this.clock = clock;
        this.settings = settings;
        this.stamps = stamps;
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
            remembered.clear();
            for (TranscodeSession session : sessions.values()) {
                if (session.run.running()) {
                    kill(session.run);
                }
            }
        }
    }

    /**
     * Creates a session and starts its encoder at the first segment. A session for the same edition is replaced; at
     * the cap the least recently used one is, and a remembered (quiet) session of the same edition is forgotten.
     *
     * @param spec the validated session
     * @return the new session
     * @throws SessionStartException when the encoder cannot be started
     */
    public SessionView create(SessionSpec spec) {
        List<FileStamps.Stamp> read = readStamps(spec);
        synchronized (lock) {
            remembered.values().removeIf(r -> r.spec().editionId() == spec.editionId());
            for (TranscodeSession older : List.copyOf(sessions.values())) {
                if (older.spec.editionId() == spec.editionId()) {
                    destroy(older);
                }
            }
            while (sessions.size() >= MAX_SESSIONS) {
                destroy(sessions.values().stream().min(Comparator.comparing(s -> s.lastRequest)).orElseThrow());
            }
            String id = ids.get().toString();
            TranscodeSession session = new TranscodeSession(id, spec, hlsRoot.resolve(id), clock.instant(), read);
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
     * A session's description for the playlist endpoints; counts as a request. A session that went quiet is rebuilt
     * first (without starting an encoder).
     *
     * @param id the session id
     * @return the session
     * @throws NotFoundException when the session is neither live nor rebuildable (never created, ended, replaced,
     *     pushed out, too old or its file changed)
     * @throws SegmentNotReadyException when the session must be rebuilt and its file cannot be read right now (503)
     */
    public SessionView view(String id) {
        try {
            return viewLive(id);
        } catch (NotLive e) {
            revive(id);
            return viewLive(id);
        }
    }

    private SessionView viewLive(String id) {
        synchronized (lock) {
            return view(require(id));
        }
    }

    /**
     * The file of a completed segment, waiting for it when the encoder will make it soon and restarting the encoder
     * when it will not. A session that went quiet is rebuilt first and its encoder restarted at this segment. The
     * wait counts from the request's arrival, so a slow rebuild leaves less of {@link #WAIT_MAX}.
     *
     * @param id the session id
     * @param rungId one of the session's rung ids
     * @param n the segment number
     * @return the segment file inside the session's folder
     * @throws NotFoundException when the session is neither live nor rebuildable, or the rung or segment does not
     *     exist
     * @throws SegmentNotReadyException when it was not made within {@link #WAIT_MAX}, or the session must be rebuilt
     *     and its file cannot be read right now (503)
     * @throws SessionUnavailableException when the encoder failed twice
     */
    public Path segment(String id, String rungId, int n) {
        Instant arrived = clock.instant();
        try {
            return segmentLive(id, rungId, n, arrived);
        } catch (NotLive e) {
            revive(id);
            return segmentLive(id, rungId, n, arrived);
        }
    }

    private Path segmentLive(String id, String rungId, int n, Instant arrived) {
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
        Duration left = WAIT_MAX.minus(Duration.between(arrived, clock.instant()));
        waiter.await(() -> settled(session, rungId, n), left.isNegative() ? Duration.ZERO : left);
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

    /**
     * Ends a session for good: its encoder is stopped and its folder deleted, and it is forgotten if it was only
     * remembered. Ending an unknown session does nothing.
     */
    public void end(String id) {
        synchronized (lock) {
            remembered.remove(id);
            TranscodeSession session = sessions.get(id);
            if (session != null) {
                destroy(session);
            }
        }
    }

    /** The live sessions, in the order they were made or last rebuilt, for Admin. */
    public List<SessionDtos.SessionRow> sessions() {
        synchronized (lock) {
            return sessions.values().stream().map(this::info).toList();
        }
    }

    /**
     * True while any session's encoder is running (a quiet session whose encoder stopped does not count). Background
     * work that competes with playback for the disk and the CPU, such as the subtitle timing check, waits while it is.
     */
    public boolean isEncoding() {
        synchronized (lock) {
            return sessions.values().stream().anyMatch(session -> session.run.running());
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
            expire(session);
            return;
        }
        Run run = session.run;
        if (run.running()) {
            advance(session, run);
            if (idle.compareTo(settings.idleStop()) >= 0 || tooFarAhead(session, run)) {
                stop(session, run);
            }
        } else if (idle.compareTo(settings.idleStop()) < 0 && chainDue(session)) {
            chain(session);
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
            expire(session);
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
            throw new NotLive();
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
        CutList cuts = session.spec.cuts();
        int part = cuts.partOfSegment(n);
        Integer finishedFrom = session.finishedFrom.get(part);
        if (finishedFrom != null && n >= finishedFrom) {
            return true;
        }
        return n < cuts.lastSegmentOfPart(part) && files.exists(session.segmentFile(rungId, n + 1));
    }

    private boolean settled(TranscodeSession session, String rungId, int n) {
        synchronized (lock) {
            return sessions.get(session.id) != session || session.unavailable || !session.run.running()
                || complete(session, rungId, n);
        }
    }

    /**
     * Leaves a live encoder that will reach the segment soon alone; otherwise restarts it at the segment. A segment
     * in the part after the run's is reached by the chain when the run ends, so it is waited for too.
     */
    private void ensureReaching(TranscodeSession session, int n) {
        Run run = session.run;
        if (run.running()) {
            advance(session, run);
            boolean inReach = n <= run.lastSegment || session.spec.cuts().partOfSegment(n) == run.part + 1;
            if (inReach && n >= run.startSegment && n <= run.frontier + WAIT_AHEAD_SEGMENTS) {
                return;
            }
        }
        stop(session, run);
        session.playhead = n;
        try {
            startRun(session, n);
        } catch (IOException e) {
            LOG.warn("Cannot restart the encoder for session {}: {}", session.id, e.getMessage());
            if (session.awaitingFirstRun) {
                destroy(session);
                throw new NotFoundException("No such session");
            }
            session.unavailable = true;
            throw new SessionUnavailableException();
        }
    }

    /** Starts an encoder run at a film-wide segment, on the file (part) that segment belongs to. */
    private void startRun(TranscodeSession session, int segment) throws IOException {
        SessionSpec spec = session.spec;
        CutList film = spec.cuts();
        int part = film.partOfSegment(segment);
        int last = film.lastSegmentOfPart(part);
        for (Rung rung : spec.rungs()) {
            files.deleteRange(session.dir.resolve(rung.id()), segment, last);
        }
        session.finishedFrom.remove(part);
        Run run = new Run(segment, part, last);
        SessionSpec.PartSource source = spec.parts().get(part);
        int firstSegment = film.partStartSegment(part);
        TranscodePlan plan = new TranscodePlan(source.source(), session.dir, spec.rungs(), source.audioIndex(),
            film.part(part), segment - firstSegment, transcoder.mode(), settings.threads(), source.fps(),
            spec.hardwareDecode(), settings.device(), film.partStartMs(part), firstSegment,
            part < film.partCount() - 1);
        run.process = transcoder.start(plan, new RunListener(session, run));
        session.run = run;
        session.awaitingFirstRun = false;
        session.deletedBelow = Math.min(session.deletedBelow, segment);
    }

    /** Moves the run's newest-segment mark forward over the files the first rung has written, up to its part's end. */
    private void advance(TranscodeSession session, Run run) {
        String first = session.spec.rungs().get(0).id();
        int last = run.lastSegment;
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

    /**
     * Deletes, in every rung, the last segment file of a dead run: it is half written. Only the run's own part is
     * looked at: the next part's files (from an earlier run) are not this run's and are left alone.
     */
    private void cleanPartial(TranscodeSession session, Run run) {
        advance(session, run);
        int last = run.lastSegment;
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

    /** A session went quiet: it is deleted like any other ending, and remembered so a later request can rebuild it. */
    private void expire(TranscodeSession session) {
        destroy(session);
        remember(session);
    }

    /** Keeps what rebuilding the session needs; never an unavailable session or one whose files could not be read. */
    private void remember(TranscodeSession session) {
        if (session.unavailable || session.stamps == null) {
            return;
        }
        remembered.put(session.id, new Remembered(session.id, session.dir, session.spec, session.stamps,
            session.startedAt, session.lastRequest, session.playhead, session.lastRung));
        while (remembered.size() > MAX_REMEMBERED) {
            Iterator<Remembered> eldest = remembered.values().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    /** Reads every part's stamp, outside the lock; null when any of them cannot be read. */
    private List<FileStamps.Stamp> readStamps(SessionSpec spec) {
        List<FileStamps.Stamp> read = new ArrayList<>();
        for (SessionSpec.PartSource part : spec.parts()) {
            try {
                read.add(stamps.read(part.source()));
            } catch (IOException e) {
                return null;
            }
        }
        return read;
    }

    /**
     * Makes a quiet session live again under its own id, from what was remembered and nothing from the request: the
     * id string, folder and spec are the remembered record's. The files are read outside the lock (the share can be
     * slow), then everything is decided again under it. Returns when the id is live; throws {@link NotFoundException}
     * when it is not rebuildable and {@link SegmentNotReadyException} when a file cannot be read just now. Package
     * private so a test can show the step that finds the id already live (a request that rebuilt it first).
     */
    void revive(String id) {
        Remembered first;
        synchronized (lock) {
            if (sessions.containsKey(id)) {
                return;
            }
            first = remembered.get(id);
            if (first == null) {
                throw new NotFoundException("No such session");
            }
            if (tooOld(first, clock.instant())) {
                remembered.remove(id);
                throw new NotFoundException("No such session");
            }
        }
        boolean gone = false;
        boolean unreadable = false;
        List<FileStamps.Stamp> read = new ArrayList<>();
        for (SessionSpec.PartSource part : first.spec().parts()) {
            try {
                read.add(stamps.read(part.source()));
            } catch (NoSuchFileException e) {
                gone = true;
                read.add(null);
            } catch (IOException e) {
                unreadable = true;
                read.add(null);
            }
        }
        synchronized (lock) {
            if (sessions.containsKey(id)) {
                return;
            }
            Instant now = clock.instant();
            Remembered r = remembered.get(id);
            if (r == null) {
                throw new NotFoundException("No such session");
            }
            if (tooOld(r, now) || gone || changed(r.stamps(), read)) {
                remembered.remove(id);
                throw new NotFoundException("No such session");
            }
            if (unreadable) {
                throw new SegmentNotReadyException("The film's file cannot be read right now");
            }
            remembered.remove(id);
            while (sessions.size() >= MAX_SESSIONS) {
                destroy(sessions.values().stream().min(Comparator.comparing(s -> s.lastRequest)).orElseThrow());
            }
            files.deleteTree(r.dir());
            sessions.put(r.id(), TranscodeSession.rebuilt(r.id(), r.spec(), r.dir(), r.startedAt(), now, r.stamps(),
                r.lastRung(), r.playhead()));
            LOG.info("Rebuilt session {} after {} quiet", r.id(), Duration.between(r.lastRequest(), now));
        }
    }

    /** True when the session's last request was {@link #REMEMBER_FOR} or more before {@code now}. */
    private static boolean tooOld(Remembered r, Instant now) {
        return Duration.between(r.lastRequest(), now).compareTo(REMEMBER_FOR) >= 0;
    }

    /** True when a stamp that was read differs from the remembered one; a part that could not be read is skipped. */
    private static boolean changed(List<FileStamps.Stamp> kept, List<FileStamps.Stamp> read) {
        for (int i = 0; i < read.size(); i++) {
            if (read.get(i) != null && !read.get(i).equals(kept.get(i))) {
                return true;
            }
        }
        return false;
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
            session.finishedFrom.put(run.part, run.startSegment);
            if (chainDue(session)) {
                chain(session);
            }
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

    /**
     * True when the session's run ended cleanly before the last part and the player is near enough to the next part
     * to want it: further than {@link #LOOK_AHEAD} ahead of the player it waits (the player's request, or a later
     * sweep once the player has caught up, starts it).
     */
    private boolean chainDue(TranscodeSession session) {
        Run run = session.run;
        CutList cuts = session.spec.cuts();
        if (run.status != Status.FINISHED || session.unavailable || run.part + 1 >= cuts.partCount()) {
            return false;
        }
        long ahead = cuts.startMs(cuts.partStartSegment(run.part + 1)) - cuts.startMs(session.playhead);
        return ahead <= LOOK_AHEAD.toMillis();
    }

    /** Starts the run of the part after the finished run's, at that part's first segment. */
    private void chain(TranscodeSession session) {
        CutList cuts = session.spec.cuts();
        try {
            startRun(session, cuts.partStartSegment(session.run.part + 1));
        } catch (IOException e) {
            LOG.warn("Cannot start the next part's encoder for session {}: {}", session.id, e.getMessage());
            session.unavailable = true;
        }
    }

    /** What a quiet session needs to be rebuilt: all of it chosen by the server when the session was made. */
    private record Remembered(String id, Path dir, SessionSpec spec, List<FileStamps.Stamp> stamps, Instant startedAt,
                              Instant lastRequest, int playhead, String lastRung) {
    }

    /** The session is not live; only {@link #require} throws it, so callers can tell it from other 404s. */
    private static final class NotLive extends NotFoundException {

        private static final long serialVersionUID = 1L;

        NotLive() {
            super("No such session");
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
