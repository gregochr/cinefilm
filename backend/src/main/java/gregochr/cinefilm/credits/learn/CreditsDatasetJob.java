package gregochr.cinefilm.credits.learn;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.CreditsRefresher;
import gregochr.cinefilm.credits.PlaybackGuard;
import gregochr.cinefilm.credits.learn.DatasetStatus.State;
import gregochr.cinefilm.credits.learn.DatasetStatus.Waiting;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Reads, for every film with a usable Plex credits marker, the frames the credits classifier is trained on, onto disk
 * (see {@code docs/engineering/credits-classifier-plan.md}, "Frames: the dataset job"). Off unless
 * {@code cinefilm.credits.dataset.enabled} is true. It lives in the server because only the server can see playback: it
 * stands aside for it exactly as the credits detection does ({@link PlaybackGuard}, checked before every ffmpeg
 * call), and for the detection's own due films. It writes no database rows and takes no library lock: before a file and
 * after its last ffmpeg call it checks that the file is still present with the same fingerprint.
 *
 * <p>One file per task on the credits executor, and each task submits the next, so the credits detection's worker
 * (which runs on the same single thread) gets in between two files. A file's frames go into
 * {@code <dir>/frames/<mediaFileId>/}; {@code complete.json} is written last, and a start skips only the files whose
 * {@code complete.json} holds the current fingerprint. A {@code running} marker in {@code <dir>} makes the job start
 * again after a restart; it is removed when the job is done, out of space or stopped, and kept while the job waits or
 * gave up on an unreadable share.
 *
 * <p><strong>Concurrency.</strong> One monitor guards every state transition and the work runs outside it. Each start
 * takes a new generation; every task and every checkpoint carries the generation it was made with, and one of an older
 * generation exits (or throws {@code Stopped}) without touching state or counters. A stop takes effect at once, by
 * moving to a new generation; the task still in ffmpeg stops at its next checkpoint and deletes its partial directory.
 * The single executor thread keeps an old task and a new one from running side by side.
 *
 * <p>A start only takes over under the lock (so the 404 and the 409 are immediate) and answers {@code RUNNING}; its
 * preparation (the directories, the ffmpeg version, the targets, the sweep of {@code frames/} and the marker) is a task
 * on the credits executor, like every file. So {@code prepare} and {@code sweep} run only on the credits thread, and a
 * stop followed by a second start cannot have two preparations, or a preparation and a file, touching
 * {@code frames/} at once. A preparation that fails shows as {@code FAILED} in the next status.
 *
 * <p>The JVM's own PNG and JPEG encoding runs at the server's normal priority; only ffmpeg runs at the job's niceness.
 */
@Service
public class CreditsDatasetJob {

    /** The order of the start-up listener: after the credits refresher's, which wipes the reader's directory. */
    public static final int STARTUP_ORDER = CreditsRefresher.STARTUP_ORDER + 1;
    /** How many files in a row may fail before the job gives up. */
    static final int MAX_FAILURES_IN_A_ROW = 3;

    private static final Logger LOG = LoggerFactory.getLogger(CreditsDatasetJob.class);
    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);
    /** Stands for "no file to read" (the run is done or was stopped), so no method returns a null. */
    private static final DatasetTarget NONE = new DatasetTarget(-1, "", "", 0, 0);

    /** Thrown by a checkpoint of an older generation: the job was stopped or started again. Not an error. */
    private static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stopped() {
            super("The dataset job was stopped", null, false, false);
        }
    }

    /** A resume that finds the job started or stopped since it was queued is refused by {@link #begin}. */
    private static final class Declined extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Declined() {
            super("The resume is no longer wanted", null, false, false);
        }
    }

    /** What a start works out before the chain begins. */
    private record Prepared(String ffmpegVersion, int targets, int alreadyComplete, List<DatasetTarget> todo) {
    }

    private final DatasetTargets targets;
    private final CreditsFrameReader reader;
    private final LibraryRoot root;
    private final PlaybackGuard guard;
    private final CreditsRefresher refresher;
    private final CommandRunner runner;
    private final GitCommit git;
    private final DiskSpace disk;
    private final DatasetProperties props;
    private final Clock clock;
    private final Executor executor;

    private final Object lock = new Object();
    // Everything below is guarded by lock.
    private State state = State.IDLE;
    private Waiting waitingFor;
    private long generation;
    private List<DatasetTarget> cursor = List.of();
    private int next;
    private int total;
    private int alreadyComplete;
    private int complete;
    private int failed;
    private int skipped;
    private int yields;
    private int failuresInARow;
    private final Set<Long> failedIds = new HashSet<>();
    private Long currentMediaFileId;
    private String ffmpegVersion = "";
    private Instant startedAt;
    private Instant finishedAt;
    private String lastError;

    /** Creates the job; the executor is {@code creditsExecutor}. */
    public CreditsDatasetJob(DatasetTargets targets, CreditsFrameReader reader, LibraryRoot root,
                             PlaybackGuard guard, CreditsRefresher refresher, CommandRunner runner, GitCommit git,
                             DiskSpace disk, DatasetProperties props, Clock clock,
                             @Qualifier("creditsExecutor") Executor executor) {
        this.targets = targets;
        this.reader = reader;
        this.root = root;
        this.guard = guard;
        this.refresher = refresher;
        this.runner = runner;
        this.git = git;
        this.disk = disk;
        this.props = props;
        this.clock = clock;
        this.executor = executor;
    }

    // ---- The routes ----------------------------------------------------------------------------------------------

    /**
     * Starts a run. Only the take-over happens here, under the lock; the rest is one task on the credits executor, so
     * that every change to {@code frames/} is made on the single credits thread: it makes the directories, reads the
     * ffmpeg version, loads the targets, skips the files that already hold a current {@code complete.json}, deletes the
     * directories that are not a finished read, writes the marker and carries on with the first file. A failure there
     * leaves the state {@code FAILED} with {@code lastError}, which shows in the next status.
     *
     * @return the status: usually {@code RUNNING} with nothing counted yet; {@code FAILED} at once if the credits
     *     thread refuses the task; a preparation that fails later shows as {@code FAILED} in the next status
     * @throws NotFoundException when the job is switched off
     * @throws ConflictException when a run is going
     */
    public DatasetStatus start() {
        long gen = admit(false);
        try {
            executor.execute(() -> prepareAndGo(gen));
        } catch (RuntimeException e) {
            fail(gen, describe(e));
        }
        return status();
    }

    private long admit(boolean resumeOnly) {
        if (!props.enabled()) {
            throw new NotFoundException("The dataset job is off");
        }
        return begin(resumeOnly);
    }

    /** The preparation of a run, then its first file. Runs on the credits thread; a stale generation exits at once. */
    private void prepareAndGo(long gen) {
        if (!isRunning(gen)) {
            return;
        }
        try {
            if (props.overlapsReaderDir()) {
                fail(gen, "The dataset directory or the output directory is inside the credits detection's work "
                    + "directory, which is wiped at start-up (cinefilm.credits.dataset.dir, "
                    + "cinefilm.credits.dataset.out-dir)");
                return;
            }
            adopt(gen, prepare());
            proceed(gen);
        } catch (Throwable e) {
            // As in task(): nothing may escape onto the credits thread and leave the run RUNNING with no task.
            fail(gen, describe(e));
        }
    }

    /**
     * Stops at once: removes the marker, forgets a wait and moves to a new generation, so a task in ffmpeg stops at its
     * next checkpoint and deletes its partial directory, and a queued task exits. Never refused. A job that is off, or
     * that is not running or waiting, keeps its state (a finished or failed job is not turned into a stopped one).
     *
     * @return the status
     */
    public DatasetStatus stop() {
        if (props.enabled()) {
            synchronized (lock) {
                generation++;
                deleteMarker();
                if (state == State.RUNNING || state == State.WAITING) {
                    state = State.STOPPED;
                    waitingFor = null;
                    currentMediaFileId = null;
                    finishedAt = clock.instant();
                }
            }
        }
        return status();
    }

    /** Resumes a run that was left waiting for playback or for the credits detection; does nothing otherwise. */
    public void startIfDeferred() {
        long gen;
        synchronized (lock) {
            if (state != State.WAITING) {
                return;
            }
            state = State.RUNNING;
            waitingFor = null;
            yields = 0;
            // A new generation: a task still unwinding from the wait must not also carry the chain on.
            gen = ++generation;
        }
        try {
            proceed(gen);
        } catch (RuntimeException e) {
            fail(gen, describe(e));
        }
    }

    /**
     * At start-up, after the credits refresher's listener (which wipes the reader's directory): when the marker says a
     * run was going, or a directory is misplaced, queues the start on the credits executor, behind the refresher's own
     * start-up worker and off the main thread. Nothing is read or queried here.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(STARTUP_ORDER)
    public void onStartup() {
        if (props.enabled() && (props.overlapsReaderDir() || Files.exists(props.marker()))) {
            executor.execute(this::resume);
        }
    }

    /** Runs on the credits thread, so it prepares inline instead of queueing a second task. */
    private void resume() {
        long gen;
        try {
            gen = admit(true);
        } catch (Declined e) {
            LOG.info("Credits dataset job: not starting again: stopped or started meanwhile");
            return;
        }
        prepareAndGo(gen);
        // The state is read under the lock, not through status(), which asks the database and the disk.
        State state;
        synchronized (lock) {
            state = this.state;
        }
        LOG.info("Credits dataset job: {}", state == State.RUNNING ? "starting again" : "not starting again: " + state);
    }

    /**
     * The status.
     *
     * @return the status, with the credits detection's count and the free disk read now
     */
    public DatasetStatus status() {
        int due = props.enabled() ? refresher.dueNotHeldBack() : 0;
        Long free = freeBytes();
        synchronized (lock) {
            return new DatasetStatus(props.enabled(), state, waitingFor, total, alreadyComplete, complete, failed,
                skipped, Math.max(0, cursor.size() - complete - skipped - failed), currentMediaFileId, due, free,
                props.minFreeBytes(), startedAt, finishedAt, lastError);
        }
    }

    private Long freeBytes() {
        try {
            return disk.usableBytes(props.framesDir());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Starting ------------------------------------------------------------------------------------------------

    /**
     * Takes a new generation and resets the run; refused while one is going. For a queued resume ({@code resumeOnly})
     * it is also refused, with {@link Declined}, unless the job is still IDLE and the marker (or a misplaced directory)
     * is still there: the check and the take-over are one step under the lock.
     */
    private long begin(boolean resumeOnly) {
        synchronized (lock) {
            // The task may run long after it was queued: a stop deletes the marker, and a start leaves IDLE. The check
            // and the take-over are one step, so a stop cannot land between them.
            if (resumeOnly && (state != State.IDLE || !props.overlapsReaderDir() && !Files.exists(props.marker()))) {
                throw new Declined();
            }
            if (state == State.RUNNING) {
                throw new ConflictException("The dataset job is already running");
            }
            state = State.RUNNING;
            waitingFor = null;
            cursor = List.of();
            next = 0;
            total = 0;
            alreadyComplete = 0;
            complete = 0;
            failed = 0;
            skipped = 0;
            yields = 0;
            failuresInARow = 0;
            failedIds.clear();
            currentMediaFileId = null;
            startedAt = clock.instant();
            finishedAt = null;
            lastError = null;
            return ++generation;
        }
    }

    private Prepared prepare() throws IOException {
        Files.createDirectories(props.framesDir());
        Files.createDirectories(props.outDir());
        String version = readFfmpegVersion();
        List<DatasetTarget> all = targets.targets();
        Map<String, DatasetTarget> byName = new HashMap<>();
        List<DatasetTarget> todo = new ArrayList<>();
        int current = 0;
        for (DatasetTarget target : all) {
            byName.put(frameDir(target).getFileName().toString(), target);
            if (DatasetComplete.isCurrent(frameDir(target), target.fingerprint())) {
                current++;
            } else {
                todo.add(target);
            }
        }
        sweep(byName);
        return new Prepared(version, all.size(), current, List.copyOf(todo));
    }

    /**
     * Deletes the frame directories that are not a finished read of the file they are for: those with no parseable
     * {@code complete.json} of the current format (partial, old, unparseable), and those of a current target whose
     * {@code complete.json} holds another fingerprint. A finished directory of a file that is not a target now (it may
     * be briefly missing or unplayable) and anything that is not a directory are left alone.
     */
    private void sweep(Map<String, DatasetTarget> byName) throws IOException {
        int kept = 0;
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(props.framesDir(), Files::isDirectory)) {
            for (Path dir : existing) {
                Optional<String> fingerprint = DatasetComplete.fingerprint(dir);
                DatasetTarget target = byName.get(dir.getFileName().toString());
                if (fingerprint.isEmpty() || target != null && !fingerprint.get().equals(target.fingerprint())) {
                    deleteTree(dir);
                } else if (target == null) {
                    kept++;
                }
            }
        }
        LOG.info("Credits dataset job: {} finished directories of files that are not targets now were left alone",
            kept);
    }

    private String readFfmpegVersion() throws IOException {
        CommandRunner.Result result;
        try {
            result = runner.run(List.of(props.ffmpeg(), "-version"), VERSION_TIMEOUT);
        } catch (IOException e) {
            throw new IOException("ffmpeg could not be run: " + e.getMessage(), e);
        }
        String first = result.succeeded() ? result.stdout().lines().findFirst().orElse("") : "";
        if (first.isBlank()) {
            throw new IOException("ffmpeg -version gave no answer");
        }
        return first;
    }

    /** Makes the prepared run the current one and writes the marker, unless a stop came first. */
    private void adopt(long gen, Prepared prepared) {
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            try {
                Files.writeString(props.marker(), clock.instant().toString());
            } catch (IOException e) {
                failLocked("The running marker could not be written: " + e.getMessage());
                return;
            }
            ffmpegVersion = prepared.ffmpegVersion();
            total = prepared.targets();
            alreadyComplete = prepared.alreadyComplete();
            cursor = prepared.todo();
        }
    }

    // ---- The chain: one file per task ----------------------------------------------------------------------------

    private void submit(long gen) {
        executor.execute(() -> task(gen));
    }

    /** Submits the next task, unless this run was stopped or started again, or has ended or waits. */
    private void proceed(long gen) {
        synchronized (lock) {
            if (gen != generation || state != State.RUNNING) {
                return;
            }
        }
        submit(gen);
    }

    /** True while this task's chain is the live one: its generation is current and the run is going. */
    private boolean isRunning(long gen) {
        synchronized (lock) {
            return gen == generation && state == State.RUNNING;
        }
    }

    /** One task. Nothing escapes it: anything unexpected ends the chain with {@code FAILED}. */
    private void task(long gen) {
        try {
            step(gen);
        } catch (Throwable e) {
            fail(gen, describe(e));
        }
    }

    /** One file: each way out either submits the next task through {@link #proceed} or ends the chain. */
    private void step(long gen) {
        if (!isRunning(gen)) {
            return;
        }
        try {
            if (disk.usableBytes(props.framesDir()) < props.minFreeBytes()) {
                finish(gen, State.NO_SPACE, null);
                return;
            }
        } catch (IOException e) {
            finish(gen, State.NO_SPACE, "The free disk space could not be read: " + e.getMessage());
            return;
        }
        if (refresher.dueNotHeldBack() > 0) {
            yieldToRefresher(gen);
            return;
        }
        DatasetTarget target = nextTarget(gen);
        if (target == NONE) {
            return;
        }
        if (targets.stillCurrent(target)) {
            read(gen, target);
        } else {
            record(gen, Outcome.SKIPPED);
        }
        proceed(gen);
    }

    /** The first time in a row, the refresher's worker is queued ahead of the next task; the second time, we wait. */
    private void yieldToRefresher(long gen) {
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            if (yields > 0) {
                waitLocked(Waiting.REFRESHER);
                return;
            }
            yields = 1;
        }
        refresher.startDue();
        proceed(gen);
    }

    /** The next file not yet done or failed in this run; {@link #NONE} when there is none or the run was stopped. */
    private DatasetTarget nextTarget(long gen) {
        synchronized (lock) {
            if (gen != generation) {
                return NONE;
            }
            while (next < cursor.size() && failedIds.contains(cursor.get(next).mediaFileId())) {
                next++;
            }
            if (next >= cursor.size()) {
                endLocked(State.DONE, null);
                deleteMarker();
                return NONE;
            }
            currentMediaFileId = cursor.get(next).mediaFileId();
            return cursor.get(next);
        }
    }

    /** Reads one file's frames and counts the outcome; a wait or a stop leaves the run for {@link #proceed} to see. */
    private void read(long gen, DatasetTarget target) {
        Path dir = frameDir(target);
        try {
            Path file = root.resolveFile(target.path())
                .orElseThrow(() -> new CreditsFrameReader.ReadException("The film file cannot be found"));
            deleteTree(dir);
            Runnable checkpoint = () -> {
                if (!isCurrent(gen)) {
                    throw new Stopped();
                }
                guard.check();
            };
            DatasetFrames.Written written;
            try (CreditsFrameReader.Job job = reader.open(file, target.durationMs(), checkpoint, props.nice())) {
                written = DatasetFrames.write(job, target, dir, checkpoint);
            }
            if (targets.stillCurrent(target)) {
                // No ffmpeg call follows, so playback no longer matters; only a stop does.
                if (!isCurrent(gen)) {
                    throw new Stopped();
                }
                DatasetComplete.write(dir, completeJson(target, written), (temp, done) -> commit(gen, temp, done));
                record(gen, Outcome.COMPLETE);
            } else {
                deleteTree(dir);
                record(gen, Outcome.SKIPPED);
            }
        } catch (PlaybackGuard.Busy busy) {
            deleteTree(dir);
            LOG.info("Credits dataset job waits: {}", busy.getMessage());
            synchronized (lock) {
                if (gen == generation) {
                    waitLocked(Waiting.PLAYBACK);
                }
            }
        } catch (Stopped stopped) {
            deleteTree(dir);
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            LOG.warn("Could not read the frames of file {}: {}", target.mediaFileId(), describe(e));
            recordFailure(gen, target);
        }
    }

    /** The final move of a {@code complete.json}, made under the monitor so that a start cannot slip in before it. */
    private void commit(long gen, Path temp, Path target) throws IOException {
        synchronized (lock) {
            if (gen != generation) {
                throw new Stopped();
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private DatasetComplete completeJson(DatasetTarget target, DatasetFrames.Written written) {
        long duration = target.durationMs();
        String version;
        synchronized (lock) {
            version = ffmpegVersion;
        }
        return new DatasetComplete(DatasetComplete.FORMAT, target.mediaFileId(), target.fingerprint(), duration,
            target.targetStartMs(),
            new DatasetComplete.Window(duration - CreditsFrameReader.windowMs(duration), duration),
            new DatasetComplete.Frame(written.frameWidth(), written.frameHeight()),
            new DatasetComplete.Area(written.area().left(), written.area().top(), written.area().width(),
                written.area().height()),
            written.activeAreaOffsets(), written.coarse(), written.dense(), version, Runtime.version().toString(),
            git.commit(), DatasetComplete.Settings.current(props.nice()), clock.instant().toString());
    }

    // ---- State transitions (all under the monitor) ----------------------------------------------------------------

    /** A file's end that is not a failure. */
    private enum Outcome {
        COMPLETE, SKIPPED
    }

    /** Counts a file done or skipped, while this is still the current generation. */
    private void record(long gen, Outcome outcome) {
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            if (outcome == Outcome.COMPLETE) {
                complete++;
            } else {
                skipped++;
            }
            next++;
            yields = 0;
            failuresInARow = 0;
        }
    }

    /** Counts a file failed, while this is still the current generation; the third in a row ends the run. */
    private void recordFailure(long gen, DatasetTarget target) {
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            failed++;
            failedIds.add(target.mediaFileId());
            yields = 0;
            failuresInARow++;
            if (failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                failLocked(MAX_FAILURES_IN_A_ROW + " files in a row could not be read; is the library share mounted?");
            }
        }
    }

    private boolean isCurrent(long gen) {
        synchronized (lock) {
            return gen == generation;
        }
    }

    /** Leaves for later; the marker stays, and the hourly nudge or a start resumes. */
    private void waitLocked(Waiting reason) {
        state = State.WAITING;
        waitingFor = reason;
        currentMediaFileId = null;
        finishedAt = null;
    }

    private void finish(long gen, State end, String error) {
        synchronized (lock) {
            if (gen == generation) {
                endLocked(end, error);
                deleteMarker();
            }
        }
    }

    /** Ends the run with {@code FAILED}; the marker is deleted, as a start that failed. */
    private void fail(long gen, String error) {
        synchronized (lock) {
            if (gen == generation) {
                failLocked(error);
                deleteMarker();
            }
        }
    }

    /** Ends the run with {@code FAILED}, keeping the marker, so a restart resumes. */
    private void failLocked(String error) {
        endLocked(State.FAILED, error);
    }

    private void endLocked(State end, String error) {
        state = end;
        waitingFor = null;
        currentMediaFileId = null;
        lastError = error;
        finishedAt = clock.instant();
    }

    // ---- Files ---------------------------------------------------------------------------------------------------

    private Path frameDir(DatasetTarget target) {
        return props.framesDir().resolve(Long.toString(target.mediaFileId()));
    }

    private void deleteMarker() {
        try {
            Files.deleteIfExists(props.marker());
        } catch (IOException e) {
            LOG.warn("The running marker could not be removed: {}", describe(e));
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not remove a dataset directory: {}", describe(e));
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
