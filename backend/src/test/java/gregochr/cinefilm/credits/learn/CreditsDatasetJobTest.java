package gregochr.cinefilm.credits.learn;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.credits.CreditsDetectionService;
import gregochr.cinefilm.credits.CreditsDetectionService.DueFile;
import gregochr.cinefilm.credits.CreditsDetectionService.SaveOutcome;
import gregochr.cinefilm.credits.CreditsFrameReader;
import gregochr.cinefilm.credits.CreditsRefresher;
import gregochr.cinefilm.credits.CreditsResult;
import gregochr.cinefilm.credits.CreditsVerdict;
import gregochr.cinefilm.credits.FakeFfmpeg;
import gregochr.cinefilm.credits.GreyFrame;
import gregochr.cinefilm.credits.PlaybackGuard;
import gregochr.cinefilm.credits.learn.DatasetStatus.State;
import gregochr.cinefilm.credits.learn.DatasetStatus.Waiting;
import gregochr.cinefilm.library.CommandRunner;
import gregochr.cinefilm.library.LibraryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The credits dataset job: its states, its resume rules, its yielding to playback and to the credits detection, the
 * disk floor, and every race between a start, a stop and a task already queued. Runs on a queue the test drains: the
 * chain re-submits itself, so a direct executor would never return.
 */
class CreditsDatasetJobTest {

    /** A 100 second film: its window is the last 40 s, from 60 s, so 8 coarse frames. */
    private static final long LENGTH = 100_000;
    private static final long TARGET_START = 95_000;
    private static final long GB = 1_000_000_000L;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @TempDir
    Path temp;

    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final FakeFfmpeg ffmpeg = new FakeFfmpeg();
    private final FakeTargets targets = new FakeTargets();
    private final FakeDisk disk = new FakeDisk();
    private final ScriptedRunner runner = new ScriptedRunner(ffmpeg);
    private final PlaybackGuard guard = mock(PlaybackGuard.class);
    private final CreditsRefresher refresher = mock(CreditsRefresher.class);
    private final LibraryRoot root = mock(LibraryRoot.class);
    private final GitCommit git = mock(GitCommit.class);
    private final Set<String> unresolvable = new HashSet<>();
    private final Deque<Integer> dueScript = new ArrayDeque<>();
    private final Logger jobLog = (Logger) LoggerFactory.getLogger(CreditsDatasetJob.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Executor executor = queue::add;
    private int guardCalls;
    private int busyAtCall;
    private Runnable onGuardCall = () -> { };
    private DatasetProperties props;
    private CreditsDatasetJob job;

    /** A stand-in for ffmpeg that also answers {@code -version}, and can fail it. */
    private static final class ScriptedRunner implements CommandRunner {
        private final FakeFfmpeg frames;
        private final List<List<String>> versionCommands = new ArrayList<>();
        private final List<Duration> versionTimeouts = new ArrayList<>();
        private Result version = new Result(0, "ffmpeg version 7.1.1 Copyright (c) 2000-2025\nbuilt with gcc\n", false);
        private IOException versionFailure;

        ScriptedRunner(FakeFfmpeg frames) {
            this.frames = frames;
        }

        @Override
        public Result run(List<String> command, Duration timeout) throws IOException {
            if (command.contains("-version")) {
                versionCommands.add(command);
                versionTimeouts.add(timeout);
                if (versionFailure != null) {
                    throw versionFailure;
                }
                return version;
            }
            return frames.run(command, timeout);
        }
    }

    /** The database side, set by hand. */
    private static final class FakeTargets extends DatasetTargets {
        private List<DatasetTarget> list = List.of();
        private final Set<Long> changedBeforeRead = new HashSet<>();
        private final Set<Long> changedAfterRead = new HashSet<>();
        private final Map<Long, Integer> calls = new HashMap<>();
        private RuntimeException currentFailure;
        private RuntimeException listFailure;
        private Runnable duringList = () -> { };
        private Runnable duringCurrent = () -> { };
        private int listCalls;

        FakeTargets() {
            super(null, null, null);
        }

        @Override
        public List<DatasetTarget> targets() {
            listCalls++;
            duringList.run();
            if (listFailure != null) {
                throw listFailure;
            }
            return list;
        }

        @Override
        public boolean stillCurrent(DatasetTarget target) {
            duringCurrent.run();
            if (currentFailure != null) {
                throw currentFailure;
            }
            int call = calls.merge(target.mediaFileId(), 1, Integer::sum);
            return call == 1 ? !changedBeforeRead.contains(target.mediaFileId())
                : !changedAfterRead.contains(target.mediaFileId());
        }
    }

    /** The disk, set by hand. */
    private static final class FakeDisk extends DiskSpace {
        private long free = 100 * GB;
        private IOException failure;
        private Path last;
        private Runnable duringRead = () -> { };
        private int reads;

        @Override
        public long usableBytes(Path dir) throws IOException {
            reads++;
            last = dir;
            duringRead.run();
            if (failure != null) {
                throw failure;
            }
            return free;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ffmpeg.denseCount = 2;
        ffmpeg.frameAt = ms -> new GreyFrame(4, 2, new byte[] {100, 100, 100, 100, 100, 100, 100, 100});
        props = props(temp.resolve("dataset"), true);
        job = job(props);
        when(root.resolveFile(anyString())).thenAnswer(call -> unresolvable.contains(call.<String>getArgument(0))
            ? Optional.empty() : Optional.of(Path.of("/library/" + call.<String>getArgument(0))));
        when(git.commit()).thenReturn("0123456789abcdef0123456789abcdef01234567");
        when(refresher.dueNotHeldBack()).thenAnswer(call -> dueScript.isEmpty() ? 0 : dueScript.poll());
        doAnswer(call -> {
            guardCalls++;
            onGuardCall.run();
            if (guardCalls == busyAtCall) {
                throw new PlaybackGuard.Busy("a film is being transcoded");
            }
            return null;
        }).when(guard).check();
        logged.start();
        jobLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        jobLog.detachAppender(logged);
    }

    private DatasetProperties props(Path dataset, boolean enabled) {
        return new DatasetProperties(enabled, dataset.toString(), temp.resolve("out").toString(), 19, 15,
            "ffmpeg", temp.resolve("cache").toString());
    }

    private CreditsDatasetJob job(DatasetProperties properties) {
        CreditsFrameReader reader = new CreditsFrameReader(runner, "ffmpeg", 10, temp.resolve("cache").toString());
        return new CreditsDatasetJob(targets, reader, root, guard, refresher, runner, git, disk, properties, CLOCK,
            task -> executor.execute(task));
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private static DatasetTarget target(long id) {
        return new DatasetTarget(id, "f" + id + ".m4v", "fp" + id, LENGTH, TARGET_START);
    }

    private void targetIds(long... ids) {
        List<DatasetTarget> list = new ArrayList<>();
        for (long id : ids) {
            list.add(target(id));
        }
        targets.list = list;
    }

    /**
     * Starts a run and runs just its preparation task (the last one queued), as the credits thread would, then reads
     * the status. Tasks already queued stay where they are.
     */
    private DatasetStatus startNow(CreditsDatasetJob target) {
        int queued = queue.size();
        target.start();
        if (queue.size() > queued) {
            queue.pollLast().run();
        }
        return target.status();
    }

    private void drain() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    private Path frames(long id) {
        return props.framesDir().resolve(Long.toString(id));
    }

    private List<String> warnings() {
        return logged.list.stream().filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private DatasetComplete readComplete(long id) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(frames(id).resolve("complete.json")), DatasetComplete.class);
    }

    /** Puts a finished-looking directory for a target in place, with the given fingerprint and format. */
    private void finished(long id, String fingerprint) throws IOException {
        Files.createDirectories(frames(id));
        Files.writeString(frames(id).resolve("complete.json"),
            "{\"format\": 1, \"fingerprint\": \"" + fingerprint + "\"}");
    }

    private List<String> frameDirs() throws IOException {
        try (Stream<Path> list = Files.list(props.framesDir())) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    // ---- A whole run ---------------------------------------------------------------------------------------------

    @Test
    void aRunReadsEveryTargetOneTaskAtATimeAndEndsDoneWithTheMarkerRemoved() throws IOException {
        targetIds(1, 2, 3);

        DatasetStatus started = startNow(job);

        assertThat(started.state()).isEqualTo(State.RUNNING);
        assertThat(started.enabled()).isTrue();
        assertThat(started.targets()).isEqualTo(3);
        assertThat(started.alreadyComplete()).isZero();
        assertThat(started.remaining()).isEqualTo(3);
        assertThat(started.startedAt()).isEqualTo(NOW);
        assertThat(started.finishedAt()).isNull();
        assertThat(props.marker()).exists();
        assertThat(Files.readString(props.marker())).isEqualTo(NOW.toString());
        assertThat(props.outDir()).isDirectory();
        assertThat(queue).hasSize(1);

        queue.poll().run();
        assertThat(job.status().complete()).isEqualTo(1);
        assertThat(queue).hasSize(1);
        drain();

        DatasetStatus done = job.status();
        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.complete()).isEqualTo(3);
        assertThat(done.failed()).isZero();
        assertThat(done.skipped()).isZero();
        assertThat(done.remaining()).isZero();
        assertThat(done.currentMediaFileId()).isNull();
        assertThat(done.finishedAt()).isEqualTo(NOW);
        assertThat(done.lastError()).isNull();
        assertThat(props.marker()).doesNotExist();
        assertThat(frameDirs()).containsExactly("1", "2", "3");
        for (String id : List.of("1", "2", "3")) {
            assertThat(props.framesDir().resolve(id).resolve("complete.json")).exists();
            assertThat(props.framesDir().resolve(id).resolve("coarse")).isDirectory();
            assertThat(props.framesDir().resolve(id).resolve("dense")).isDirectory();
        }
        assertThat(frames(1).resolve("coarse/60000.png")).exists();
        assertThat(frames(1).resolve("dense/65000.jpg")).exists();
    }

    @Test
    void completeJsonHoldsExactlyWhatWasRead() throws IOException {
        targetIds(7);
        startNow(job);
        drain();

        assertThat(readComplete(7)).isEqualTo(new DatasetComplete(1, 7L, "fp7", 100_000L, 95_000L,
            new DatasetComplete.Window(60_000L, 100_000L), new DatasetComplete.Frame(4, 2),
            new DatasetComplete.Area(0, 0, 4, 2), List.of(30_000L, 37_500L, 45_000L, 52_500L, 60_000L),
            List.of(60_000L, 65_000L, 70_000L, 75_000L, 80_000L, 85_000L, 90_000L, 95_000L),
            List.of(65_000L, 65_500L), "ffmpeg version 7.1.1 Copyright (c) 2000-2025", Runtime.version().toString(),
            "0123456789abcdef0123456789abcdef01234567", DatasetComplete.Settings.current(19), NOW.toString()));
    }

    @Test
    void ffmpegIsAskedItsVersionWithATenSecondTimeout() {
        targetIds(1);

        startNow(job);

        assertThat(runner.versionCommands).containsExactly(List.of("ffmpeg", "-version"));
        assertThat(runner.versionTimeouts).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    void everyFfmpegCallOfAFileRunsUnderTheJobsOwnNiceness() {
        targetIds(1);

        startNow(job);
        drain();

        assertThat(ffmpeg.commands).isNotEmpty().allSatisfy(command ->
            assertThat(command.subList(0, 3)).containsExactly("nice", "-n", "19"));
    }

    @Test
    void theStatusCarriesTheFloorTheFreeDiskAndWhatTheRefresherHas() {
        targetIds(1);
        disk.free = 42 * GB;

        DatasetStatus status = job.status();

        assertThat(status.minFreeBytes()).isEqualTo(15 * GB);
        assertThat(status.freeBytes()).isEqualTo(42 * GB);
        assertThat(status.refresherDue()).isZero();
        assertThat(status.state()).isEqualTo(State.IDLE);
        assertThat(disk.last).isEqualTo(props.framesDir());
    }

    @Test
    void theStatusReportsWhatTheRefresherHasDueAndNullWhenTheDiskCannotBeRead() {
        dueScript.add(5);
        disk.failure = new IOException("no such device");

        DatasetStatus status = job.status();

        assertThat(status.refresherDue()).isEqualTo(5);
        assertThat(status.freeBytes()).isNull();
    }

    @Test
    void theStatusNamesTheFileBeingReadWhileAFfmpegCallIsOutstanding() {
        targetIds(4, 5);
        List<Long> seen = new ArrayList<>();
        onGuardCall = () -> seen.add(job.status().currentMediaFileId());
        startNow(job);

        queue.poll().run();

        assertThat(seen).isNotEmpty().containsOnly(4L);
    }

    // ---- Switched off --------------------------------------------------------------------------------------------

    @Test
    void whenTheJobIsOffStartIsNotFoundStopChangesNothingAndNothingStartsAtStartUp() throws IOException {
        DatasetProperties off = props(temp.resolve("dataset"), false);
        Files.createDirectories(off.dir());
        Files.writeString(off.marker(), "left behind");
        CreditsDatasetJob disabled = job(off);

        assertThatThrownBy(disabled::start).isInstanceOf(NotFoundException.class)
            .hasMessage("The dataset job is off");
        DatasetStatus stopped = disabled.stop();
        disabled.startIfDeferred();
        disabled.onStartup();

        assertThat(stopped.enabled()).isFalse();
        assertThat(stopped.state()).isEqualTo(State.IDLE);
        assertThat(disabled.status().state()).isEqualTo(State.IDLE);
        assertThat(off.marker()).exists();
        assertThat(queue).isEmpty();
        verifyNoInteractions(refresher);
    }

    // ---- Starting ------------------------------------------------------------------------------------------------

    @Test
    void startWhileRunningIs409AndTheChainIsUnchanged() {
        targetIds(1, 2);
        startNow(job);
        queue.poll().run();
        DatasetStatus before = job.status();

        assertThatThrownBy(job::start).isInstanceOf(ConflictException.class)
            .hasMessage("The dataset job is already running");

        assertThat(job.status()).isEqualTo(before);
        assertThat(queue).hasSize(1);
        drain();
        assertThat(job.status().complete()).isEqualTo(2);
    }

    @Test
    void startCreatesTheFramesAndOutputDirectories() {
        targetIds(1);

        startNow(job);

        assertThat(props.framesDir()).isDirectory();
        assertThat(props.outDir()).isDirectory();
    }

    @Test
    void aTargetWithACurrentCompleteJsonIsSkippedAndItsDirectoryIsLeftAlone() throws IOException {
        targetIds(1, 2);
        finished(1, "fp1");
        Files.writeString(frames(1).resolve("marker.txt"), "keep");

        DatasetStatus started = startNow(job);
        drain();

        assertThat(started.alreadyComplete()).isEqualTo(1);
        assertThat(started.targets()).isEqualTo(2);
        assertThat(started.remaining()).isEqualTo(1);
        DatasetStatus done = job.status();
        assertThat(done.alreadyComplete()).isEqualTo(1);
        assertThat(done.complete()).isEqualTo(1);
        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(frames(1).resolve("marker.txt")).exists();
        assertThat(frames(1).resolve("coarse")).doesNotExist();
    }

    @Test
    void aStaleFingerprintAnOldFormatAnUnparseableFileAndAPartialDirectoryAreEachRedone() throws IOException {
        targetIds(1, 2, 3, 4, 5);
        finished(1, "old-fingerprint");
        Files.createDirectories(frames(2));
        Files.writeString(frames(2).resolve("complete.json"), "{\"format\": 0, \"fingerprint\": \"fp2\"}");
        Files.createDirectories(frames(3));
        Files.writeString(frames(3).resolve("complete.json"), "{\"format\": 1, \"fingerprint\"");
        Files.createDirectories(frames(4).resolve("coarse"));
        Files.writeString(frames(4).resolve("coarse/60000.png"), "partial");
        Files.createDirectories(frames(99));

        DatasetStatus started = startNow(job);

        assertThat(started.alreadyComplete()).isZero();
        // The stale and partial directories, and one with no target at all, are deleted before the first task.
        assertThat(frameDirs()).isEmpty();
        drain();
        assertThat(job.status().complete()).isEqualTo(5);
        assertThat(frameDirs()).containsExactly("1", "2", "3", "4", "5");
        for (long id = 1; id <= 5; id++) {
            assertThat(readComplete(id).fingerprint()).isEqualTo("fp" + id);
        }
    }

    @Test
    void aStrayFileInTheFramesDirectoryIsLeftAlone() throws IOException {
        targetIds(1);
        Files.createDirectories(props.framesDir());
        Files.writeString(props.framesDir().resolve("stray.txt"), "x");

        startNow(job);

        assertThat(props.framesDir().resolve("stray.txt")).exists();
    }

    @Test
    void aFinishedDirectoryOfAFileThatIsNotATargetNowIsKeptAndCounted() throws IOException {
        targetIds(1);
        finished(77, "fp77");
        Files.createDirectories(frames(77).resolve("coarse"));
        Files.writeString(frames(77).resolve("coarse/60000.png"), "kept");

        startNow(job);

        assertThat(frames(77).resolve("complete.json")).exists();
        assertThat(frames(77).resolve("coarse/60000.png")).exists();
        assertThat(messages()).contains(
            "Credits dataset job: 1 finished directories of files that are not targets now were left alone");
    }

    @Test
    void aFinishedDirectoryWhoseFileChangedIsDeletedAndOneWithTheRightFingerprintIsKept() throws IOException {
        targetIds(1, 2);
        finished(1, "changed-since");
        finished(2, "fp2");

        DatasetStatus started = startNow(job);

        assertThat(frameDirs()).containsExactly("2");
        assertThat(started.alreadyComplete()).isEqualTo(1);
        assertThat(messages()).contains(
            "Credits dataset job: 0 finished directories of files that are not targets now were left alone");
    }

    @Test
    void aLeftOverTemporaryFileGoesWithADirectoryThatIsDeletedAndIsLeftInAKeptOne() throws IOException {
        targetIds(1, 2);
        Files.createDirectories(frames(1));
        Files.writeString(frames(1).resolve("complete.json.tmp"), "half");
        finished(2, "fp2");
        Files.writeString(frames(2).resolve("complete.json.tmp"), "half");

        startNow(job);

        assertThat(frames(1)).doesNotExist();
        assertThat(frames(2).resolve("complete.json.tmp")).exists();
    }

    @Test
    void anEmptyTargetListEndsDoneAtTheFirstTask() {
        targetIds();

        DatasetStatus started = startNow(job);
        drain();

        assertThat(started.targets()).isZero();
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void aRunThatEndedCanBeStartedAgainAndResetsItsCounters() {
        targetIds(1, 2);
        startNow(job);
        drain();
        assertThat(job.status().complete()).isEqualTo(2);

        DatasetStatus again = startNow(job);

        // Both directories hold a current complete.json now.
        assertThat(again.alreadyComplete()).isEqualTo(2);
        assertThat(again.complete()).isZero();
        assertThat(again.state()).isEqualTo(State.RUNNING);
        drain();
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isZero();
    }

    // ---- Failing to start ----------------------------------------------------------------------------------------

    @Test
    void anFfmpegVersionThatCannotBeReadFailsTheStartWithNoMarkerAndNoTask() {
        targetIds(1);
        runner.version = new CommandRunner.Result(1, "", false);

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).isEqualTo("ffmpeg -version gave no answer");
        assertThat(status.finishedAt()).isEqualTo(NOW);
        assertThat(props.marker()).doesNotExist();
        assertThat(queue).isEmpty();
    }

    @Test
    void aVersionCommandThatPrintsNothingFailsTheStart() {
        runner.version = new CommandRunner.Result(0, "", false);

        assertThat(startNow(job).lastError()).isEqualTo("ffmpeg -version gave no answer");
    }

    @Test
    void aVersionCommandThatCannotBeRunFailsTheStartWithTheReason() {
        runner.versionFailure = new IOException("No such file or directory");

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).isEqualTo("ffmpeg could not be run: No such file or directory");
        assertThat(queue).isEmpty();
    }

    @Test
    void aVersionCommandThatTimedOutFailsTheStart() {
        runner.version = new CommandRunner.Result(-1, "ffmpeg version 7", true);

        assertThat(startNow(job).state()).isEqualTo(State.FAILED);
    }

    @Test
    void targetsThatCannotBeLoadedFailTheStartAndDeleteAnOldMarker() throws IOException {
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "left behind");
        targets.listFailure = new IllegalStateException("database is down");

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).isEqualTo("database is down");
        assertThat(props.marker()).doesNotExist();
        assertThat(queue).isEmpty();
    }

    @Test
    void aFailureWithNoMessageIsReportedByItsClassName() {
        targets.listFailure = new IllegalStateException();

        assertThat(startNow(job).lastError()).isEqualTo("IllegalStateException");
    }

    @Test
    void aDatasetDirectoryThatCannotBeMadeFailsTheStart() throws IOException {
        Files.writeString(props.dir(), "a file where the directory should be");

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).isNotBlank();
    }

    @Test
    void aMarkerThatCannotBeWrittenFailsTheStart() throws IOException {
        targetIds(1);
        Files.createDirectories(props.marker());

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).startsWith("The running marker could not be written: ");
        assertThat(queue).isEmpty();
    }

    @Test
    void aDatasetDirectoryInsideTheReadersWorkDirectoryFailsTheStartBecauseItIsWipedAtStartUp() {
        DatasetProperties inside = props(temp.resolve("cache/credits/dataset"), true);
        CreditsDatasetJob overlapping = job(inside);

        DatasetStatus status = startNow(overlapping);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).contains("inside the credits detection's work directory");
        assertThat(queue).isEmpty();
        assertThat(inside.framesDir()).doesNotExist();
    }

    @Test
    void afterAFailedStartTheNextStartIsAccepted() {
        targetIds(1);
        runner.version = new CommandRunner.Result(1, "", false);
        assertThat(startNow(job).state()).isEqualTo(State.FAILED);
        runner.version = new CommandRunner.Result(0, "ffmpeg version 7", false);

        DatasetStatus status = startNow(job);
        drain();

        assertThat(status.state()).isEqualTo(State.RUNNING);
        assertThat(status.lastError()).isNull();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void anExecutorThatRefusesTheFirstTaskFailsTheStart() {
        targetIds(1);
        executor = task -> {
            throw new RejectedExecutionException("full");
        };

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).isEqualTo("full");
        assertThat(props.marker()).doesNotExist();
    }

    // ---- Preparation is a task on the credits thread ----------------------------------------------------------------

    @Test
    void startAnswersRunningWithNothingDoneUntilThePreparationTaskRuns() {
        targetIds(1);

        DatasetStatus started = job.start();

        assertThat(started.state()).isEqualTo(State.RUNNING);
        assertThat(started.targets()).isZero();
        assertThat(queue).hasSize(1);
        assertThat(runner.versionCommands).isEmpty();
        assertThat(targets.listCalls).isZero();
        assertThat(props.framesDir()).doesNotExist();
        assertThat(props.marker()).doesNotExist();

        queue.poll().run();

        assertThat(runner.versionCommands).hasSize(1);
        assertThat(job.status().targets()).isEqualTo(1);
        assertThat(props.marker()).exists();
        assertThat(queue).hasSize(1);
    }

    @Test
    void aPreparationThatFailsShowsAsFailedAfterTheQueueIsDrainedNotInTheStartsAnswer() {
        targetIds(1);
        runner.version = new CommandRunner.Result(1, "", false);

        DatasetStatus started = job.start();
        assertThat(started.state()).isEqualTo(State.RUNNING);
        drain();

        assertThat(job.status().state()).isEqualTo(State.FAILED);
        assertThat(job.status().lastError()).isEqualTo("ffmpeg -version gave no answer");
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void anErrorDuringPreparationEndsFailedAndDoesNotEscapeTheTask() {
        targetIds(1);
        targets.duringList = () -> {
            throw new AssertionError("boom");
        };
        job.start();

        drain();

        assertThat(job.status().state()).isEqualTo(State.FAILED);
        assertThat(job.status().lastError()).isEqualTo("boom");
        assertThat(queue).isEmpty();
        // Not stuck at RUNNING: a later start is accepted.
        targets.duringList = () -> { };
        job.start();
        drain();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void anErrorDuringAResumeEndsFailedAndDoesNotEscapeTheTask() throws IOException {
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        targets.duringList = () -> {
            throw new AssertionError("boom");
        };
        job.onStartup();

        drain();

        assertThat(job.status().state()).isEqualTo(State.FAILED);
        assertThat(messages()).contains("Credits dataset job: not starting again: FAILED");
    }

    @Test
    void aResumeWhoseStatusCannotBeReadLogsAWarningAndDoesNotEscapeTheTask() throws IOException {
        targetIds(1);
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        job.onStartup();
        when(refresher.dueNotHeldBack()).thenThrow(new IllegalStateException("database is down"));

        drain();

        assertThat(warnings()).containsExactly("Credits dataset job: could not start again: database is down");
    }

    @Test
    void aStopThenASecondStartWhileTheFirstPreparationIsQueuedMakesTheFirstExitWithoutTouchingTheDisk()
        throws IOException {
        targetIds(1);
        job.start();
        job.stop();
        job.start();
        assertThat(queue).hasSize(2);
        // A partial directory that the newer run will sweep, but the older preparation must not.
        Files.createDirectories(frames(9));

        queue.poll().run();

        assertThat(runner.versionCommands).isEmpty();
        assertThat(targets.listCalls).isZero();
        assertThat(frames(9)).exists();
        assertThat(queue).hasSize(1);

        drain();

        assertThat(runner.versionCommands).hasSize(1);
        assertThat(frames(9)).doesNotExist();
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(1);
    }

    // ---- Start-up ------------------------------------------------------------------------------------------------

    @Test
    void atStartUpTheJobStartsAgainOnlyWhenTheMarkerIsThere() throws IOException {
        targetIds(1);
        Files.createDirectories(props.dir());

        job.onStartup();

        assertThat(job.status().state()).isEqualTo(State.IDLE);
        assertThat(queue).isEmpty();

        Files.writeString(props.marker(), "a run was going");
        job.onStartup();

        // Nothing is done inline: one task is queued, and no ffmpeg call or target query happens until it runs.
        assertThat(queue).hasSize(1);
        assertThat(job.status().state()).isEqualTo(State.IDLE);
        assertThat(runner.versionCommands).isEmpty();
        assertThat(targets.listCalls).isZero();
        queue.poll().run();
        assertThat(targets.listCalls).isEqualTo(1);
        assertThat(job.status().state()).isEqualTo(State.RUNNING);
        assertThat(messages()).contains("Credits dataset job: starting again");
        drain();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void aQueuedResumeThatFindsTheJobStartedMeanwhileSaysSoAndChangesNothing() throws IOException {
        targetIds(1);
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        job.onStartup();
        startNow(job);

        queue.poll().run();

        assertThat(messages()).contains("Credits dataset job: not starting again: stopped or started meanwhile");
        assertThat(queue).hasSize(1);
        assertThat(job.status().state()).isEqualTo(State.RUNNING);
        assertThat(targets.listCalls).isEqualTo(1);
    }

    @Test
    void aResumeThatFailsToStartLogsTheStateItEndedIn() throws IOException {
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        targets.listFailure = new IllegalStateException("database is down");
        job.onStartup();

        drain();

        assertThat(messages()).contains("Credits dataset job: not starting again: FAILED");
    }

    @Test
    void aResumeThatIsStoppedWhileItStartsLogsTheStateItEndedIn() throws IOException {
        targetIds(1);
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        targets.duringList = once(job::stop);
        job.onStartup();

        drain();

        assertThat(messages()).contains("Credits dataset job: not starting again: STOPPED");
    }

    @Test
    void atStartUpADatasetDirectoryInsideTheReadersDirectoryIsRefusedWithAReasonOnTheExecutor() {
        CreditsDatasetJob overlapping = job(props(temp.resolve("cache/credits"), true));

        overlapping.onStartup();
        assertThat(queue).hasSize(1);
        assertThat(overlapping.status().state()).isEqualTo(State.IDLE);
        drain();

        DatasetStatus status = overlapping.status();
        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).contains("cinefilm.credits.dataset.dir");
        assertThat(queue).isEmpty();
        assertThat(messages()).contains("Credits dataset job: not starting again: FAILED");
    }

    @Test
    void anOutputDirectoryInsideTheReadersDirectoryIsRefusedToo() {
        DatasetProperties outside = new DatasetProperties(true, temp.resolve("dataset").toString(),
            temp.resolve("cache/credits/out").toString(), 19, 15, "ffmpeg", temp.resolve("cache").toString());
        CreditsDatasetJob overlapping = job(outside);

        DatasetStatus status = startNow(overlapping);

        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).contains("out-dir").contains("inside the credits detection's work directory");
        assertThat(queue).isEmpty();
        assertThat(outside.framesDir()).doesNotExist();
    }

    @Test
    void theStartUpListenerRunsAfterTheRefreshersWhichWipesTheDirectoryTheJobReadsInto() throws Exception {
        org.springframework.core.annotation.Order refresherOrder = CreditsRefresher.class.getMethod("onStartup")
            .getAnnotation(org.springframework.core.annotation.Order.class);
        org.springframework.core.annotation.Order datasetOrder = CreditsDatasetJob.class.getMethod("onStartup")
            .getAnnotation(org.springframework.core.annotation.Order.class);

        assertThat(refresherOrder.value()).isEqualTo(CreditsRefresher.STARTUP_ORDER);
        assertThat(datasetOrder.value()).isEqualTo(CreditsDatasetJob.STARTUP_ORDER)
            .isGreaterThan(refresherOrder.value());
        assertThat(CreditsDatasetJob.class.getMethod("onStartup")
            .getAnnotation(org.springframework.context.event.EventListener.class).value())
            .containsExactly(org.springframework.boot.context.event.ApplicationReadyEvent.class);
    }

    // ---- Playback ------------------------------------------------------------------------------------------------

    @Test
    void playbackPartWayDeletesThePartialDirectoryDefersAndKeepsTheMarker() throws IOException {
        targetIds(1, 2, 3);
        busyAtCall = 25;
        startNow(job);

        drain();

        DatasetStatus waiting = job.status();
        assertThat(waiting.state()).isEqualTo(State.WAITING);
        assertThat(waiting.waitingFor()).isEqualTo(Waiting.PLAYBACK);
        assertThat(waiting.complete()).isEqualTo(1);
        assertThat(waiting.failed()).isZero();
        assertThat(waiting.remaining()).isEqualTo(2);
        assertThat(waiting.currentMediaFileId()).isNull();
        assertThat(waiting.finishedAt()).isNull();
        assertThat(frameDirs()).containsExactly("1");
        assertThat(props.marker()).exists();
        assertThat(queue).isEmpty();
        assertThat(messages()).contains("Credits dataset job waits: a film is being transcoded");
    }

    @Test
    void theHourlyNudgeResumesAWaitingRunKeepingItsCounters() throws IOException {
        targetIds(1, 2, 3);
        busyAtCall = 25;
        startNow(job);
        drain();
        assertThat(job.status().complete()).isEqualTo(1);

        job.startIfDeferred();

        DatasetStatus resumed = job.status();
        assertThat(resumed.state()).isEqualTo(State.RUNNING);
        assertThat(resumed.waitingFor()).isNull();
        assertThat(resumed.complete()).isEqualTo(1);
        assertThat(resumed.targets()).isEqualTo(3);
        assertThat(queue).hasSize(1);
        drain();
        DatasetStatus done = job.status();
        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.complete()).isEqualTo(3);
        assertThat(done.startedAt()).isEqualTo(NOW);
        assertThat(frameDirs()).containsExactly("1", "2", "3");
        assertThat(readComplete(2).ffmpegVersion()).isEqualTo("ffmpeg version 7.1.1 Copyright (c) 2000-2025");
        assertThat(runner.versionCommands).hasSize(1);
    }

    @Test
    void theHourlyNudgeDoesNothingWhileTheChainIsRunning() {
        targetIds(1, 2);
        startNow(job);
        assertThat(queue).hasSize(1);

        job.startIfDeferred();

        assertThat(queue).hasSize(1);
        assertThat(job.status().state()).isEqualTo(State.RUNNING);
    }

    @Test
    void theHourlyNudgeDoesNothingBeforeAnyStartOrAfterTheRunEnded() {
        job.startIfDeferred();
        assertThat(queue).isEmpty();

        targetIds(1);
        startNow(job);
        drain();
        job.startIfDeferred();

        assertThat(queue).isEmpty();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void anExecutorThatRefusesTheResumeFailsTheRun() {
        targetIds(1, 2);
        busyAtCall = 3;
        startNow(job);
        drain();
        assertThat(job.status().state()).isEqualTo(State.WAITING);
        executor = task -> {
            throw new RejectedExecutionException("full");
        };

        job.startIfDeferred();

        assertThat(job.status().state()).isEqualTo(State.FAILED);
        assertThat(job.status().lastError()).isEqualTo("full");
        assertThat(props.marker()).doesNotExist();
    }

    // ---- Stopping ------------------------------------------------------------------------------------------------

    @Test
    void stopPartWayIsStoppedAtOnceAndTheTaskStopsAtItsNextCheckpointDeletingItsPartialDirectory()
        throws IOException {
        targetIds(1, 2, 3);
        startNow(job);
        List<DatasetStatus> right = new ArrayList<>();
        onGuardCall = () -> {
            if (guardCalls == 25) {
                right.add(job.stop());
                right.add(job.status());
            }
        };

        drain();

        // Stopped the moment stop was called, with the counters of that moment, and nothing changed afterwards.
        assertThat(right).hasSize(2).allSatisfy(status -> {
            assertThat(status.state()).isEqualTo(State.STOPPED);
            assertThat(status.complete()).isEqualTo(1);
            assertThat(status.currentMediaFileId()).isNull();
            assertThat(status.finishedAt()).isEqualTo(NOW);
        });
        assertThat(job.status()).isEqualTo(right.getFirst());
        assertThat(guardCalls).isEqualTo(25);
        assertThat(frameDirs()).containsExactly("1");
        assertThat(props.marker()).doesNotExist();
        assertThat(queue).isEmpty();
        assertThat(warnings()).isEmpty();
    }

    @Test
    void stopWhileWaitingIsStoppedClearsTheWaitAndTheHourlyNudgeThenDoesNothing() {
        targetIds(1, 2);
        busyAtCall = 3;
        startNow(job);
        drain();
        assertThat(job.status().state()).isEqualTo(State.WAITING);

        DatasetStatus stopped = job.stop();
        job.startIfDeferred();

        assertThat(stopped.state()).isEqualTo(State.STOPPED);
        assertThat(stopped.waitingFor()).isNull();
        assertThat(queue).isEmpty();
        assertThat(job.status().state()).isEqualTo(State.STOPPED);
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void stopThenStartBeforeTheOldTaskRunsAgainIsAcceptedAndTheOldTaskExitsWithoutTouchingState() {
        targetIds(1, 2);
        startNow(job);
        assertThat(queue).hasSize(1);
        job.stop();

        DatasetStatus restarted = startNow(job);

        assertThat(restarted.state()).isEqualTo(State.RUNNING);
        assertThat(queue).hasSize(2);
        // The old task (generation 1) runs first and must not read a file, check the disk or change anything.
        int diskReads = disk.reads;
        queue.poll().run();
        assertThat(ffmpeg.commands).isEmpty();
        assertThat(disk.reads).isEqualTo(diskReads);
        assertThat(job.status().state()).isEqualTo(State.RUNNING);
        assertThat(job.status().complete()).isZero();
        assertThat(queue).hasSize(1);
        drain();
        DatasetStatus done = job.status();
        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.complete()).isEqualTo(2);
        // One pass over two files: 14 reads for each.
        assertThat(ffmpeg.commands).hasSize(2 * (5 + 8 + 1));
    }

    @Test
    void aStopWhileTheStartIsStillPreparingWinsAndTheStartLeavesNoMarker() {
        targetIds(1);
        targets.duringList = job::stop;

        DatasetStatus status = startNow(job);

        assertThat(status.state()).isEqualTo(State.STOPPED);
        assertThat(queue).isEmpty();
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void stopOnARunThatIsDoneOrFailedKeepsItsStateButRemovesTheMarker() throws IOException {
        targetIds(1);
        startNow(job);
        drain();
        assertThat(job.stop().state()).isEqualTo(State.DONE);

        targets.listFailure = new IllegalStateException("database is down");
        startNow(job);
        Files.writeString(props.marker(), "x");

        assertThat(job.stop().state()).isEqualTo(State.FAILED);
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void aMarkerThatCannotBeRemovedDoesNotStopTheStopAndIsLogged() throws IOException {
        targetIds(1);
        startNow(job);
        Files.delete(props.marker());
        Files.createDirectories(props.marker());
        Files.writeString(props.marker().resolve("inside"), "x");

        DatasetStatus stopped = job.stop();

        assertThat(stopped.state()).isEqualTo(State.STOPPED);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst()).startsWith("The running marker could not be removed: ");
    }

    // ---- The disk ------------------------------------------------------------------------------------------------

    @Test
    void aDiskBelowTheFloorEndsNoSpaceWithTheMarkerRemovedAndNoFileRead() {
        targetIds(1, 2);
        disk.free = 15 * GB - 1;

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.NO_SPACE);
        assertThat(status.lastError()).isNull();
        assertThat(status.complete()).isZero();
        assertThat(status.finishedAt()).isEqualTo(NOW);
        assertThat(props.marker()).doesNotExist();
        assertThat(ffmpeg.commands).isEmpty();
    }

    @Test
    void aDiskExactlyAtTheFloorStillRuns() {
        targetIds(1);
        disk.free = 15 * GB;

        startNow(job);
        drain();

        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void theFloorIsCheckedBeforeEveryFileSoTheJobStopsWhenTheDiskFillsPartWay() {
        targetIds(1, 2, 3);
        startNow(job);
        queue.poll().run();
        disk.free = 1 * GB;

        drain();

        assertThat(job.status().state()).isEqualTo(State.NO_SPACE);
        assertThat(job.status().complete()).isEqualTo(1);
    }

    @Test
    void aDiskThatCannotBeReadEndsNoSpaceWithTheReason() {
        targetIds(1);
        disk.failure = new IOException("device not ready");

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.NO_SPACE);
        assertThat(status.lastError()).isEqualTo("The free disk space could not be read: device not ready");
        assertThat(props.marker()).doesNotExist();
        assertThat(status.freeBytes()).isNull();
    }

    // ---- Files that change or fail -------------------------------------------------------------------------------

    @Test
    void aFileThatChangedBeforeItIsReadIsSkippedWithNoFfmpegCallForIt() {
        targetIds(1, 2);
        targets.changedBeforeRead.add(1L);

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.DONE);
        assertThat(status.skipped()).isEqualTo(1);
        assertThat(status.complete()).isEqualTo(1);
        assertThat(status.remaining()).isZero();
        assertThat(frames(1)).doesNotExist();
        assertThat(ffmpeg.commands).hasSize(5 + 8 + 1);
    }

    @Test
    void aFileThatChangedWhileItWasReadHasItsDirectoryDeletedAndIsSkipped() throws IOException {
        targetIds(1, 2);
        targets.changedAfterRead.add(1L);

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.skipped()).isEqualTo(1);
        assertThat(status.complete()).isEqualTo(1);
        assertThat(status.failed()).isZero();
        assertThat(frameDirs()).containsExactly("2");
        assertThat(ffmpeg.commands).hasSize(2 * (5 + 8 + 1));
        // Asked twice (before the read and after it), never a third time: the file was counted and moved past.
        assertThat(targets.calls).containsEntry(1L, 2).containsEntry(2L, 2);
    }

    @Test
    void oneFailingFileDoesNotStopTheNextAndItsPartialDirectoryIsDeleted() throws IOException {
        targetIds(1, 2);
        ffmpeg.failOnCall = 8;

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.DONE);
        assertThat(status.failed()).isEqualTo(1);
        assertThat(status.complete()).isEqualTo(1);
        assertThat(status.remaining()).isZero();
        assertThat(frameDirs()).containsExactly("2");
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void aFailureIsLoggedWithTheMediaFileIdAndTheMessageNeverThePath() {
        targetIds(41);
        ffmpeg.failOnCall = 3;

        startNow(job);
        drain();

        assertThat(warnings()).containsExactly(
            "Could not read the frames of file 41: ffmpeg exited with status 1 reading the frame at 45.000");
        assertThat(String.join("\n", messages())).doesNotContain("f41.m4v").doesNotContain("/library");
    }

    @Test
    void aFileThatCannotBeFoundOnDiskIsAFailureAndFfmpegIsNotRunForIt() {
        targetIds(1, 2);
        unresolvable.add("f1.m4v");

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.failed()).isEqualTo(1);
        assertThat(status.complete()).isEqualTo(1);
        assertThat(warnings()).containsExactly("Could not read the frames of file 1: The film file cannot be found");
        assertThat(ffmpeg.commands).hasSize(5 + 8 + 1);
    }

    @Test
    void threeFailuresInARowEndFailedKeepingTheMarkerSoARestartResumes() throws IOException {
        targetIds(1, 2, 3, 4);
        unresolvable.addAll(List.of("f1.m4v", "f2.m4v", "f3.m4v", "f4.m4v"));

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError())
            .isEqualTo("3 files in a row could not be read; is the library share mounted?");
        assertThat(status.failed()).isEqualTo(3);
        assertThat(status.remaining()).isEqualTo(1);
        assertThat(status.finishedAt()).isEqualTo(NOW);
        assertThat(props.marker()).exists();
        assertThat(queue).isEmpty();

        // With the marker still there, a restart starts again once the share is back.
        unresolvable.clear();
        CreditsDatasetJob restarted = job(props);
        restarted.onStartup();
        drain();
        assertThat(restarted.status().state()).isEqualTo(State.DONE);
        assertThat(restarted.status().complete()).isEqualTo(4);
        assertThat(restarted.status().failed()).isZero();
    }

    @Test
    void aRunThatGaveUpCanBeStartedAgainInTheSameProcessAndForgetsItsFailures() {
        targetIds(1, 2, 3, 4);
        unresolvable.addAll(List.of("f1.m4v", "f2.m4v", "f3.m4v"));
        startNow(job);
        drain();
        assertThat(job.status().state()).isEqualTo(State.FAILED);
        unresolvable.clear();

        startNow(job);
        drain();

        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(4);
        assertThat(job.status().failed()).isZero();
    }

    @Test
    void stopOnARunThatGaveUpRemovesTheMarkerSoARestartDoesNotResume() throws IOException {
        targetIds(1, 2, 3);
        unresolvable.addAll(List.of("f1.m4v", "f2.m4v", "f3.m4v"));
        startNow(job);
        drain();
        assertThat(props.marker()).exists();

        DatasetStatus stopped = job.stop();

        assertThat(stopped.state()).isEqualTo(State.FAILED);
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void aSuccessBetweenFailuresResetsTheCountOfFailuresInARow() {
        targetIds(1, 2, 3, 4, 5, 6);
        unresolvable.addAll(List.of("f1.m4v", "f2.m4v", "f4.m4v", "f5.m4v"));

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.DONE);
        assertThat(status.failed()).isEqualTo(4);
        assertThat(status.complete()).isEqualTo(2);
    }

    @Test
    void aSkippedFileAlsoResetsTheCountOfFailuresInARow() {
        targetIds(1, 2, 3, 4, 5);
        unresolvable.addAll(List.of("f1.m4v", "f2.m4v", "f4.m4v", "f5.m4v"));
        targets.changedBeforeRead.add(3L);

        startNow(job);
        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.DONE);
        assertThat(status.failed()).isEqualTo(4);
        assertThat(status.skipped()).isEqualTo(1);
    }

    @Test
    void aFileThatFailsWritingItsFramesIsAFailureNotACrash() {
        targetIds(1, 2);
        ffmpeg.garbage = "not a picture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        startNow(job);
        drain();

        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().failed()).isEqualTo(2);
    }

    // ---- Exceptions that escape ----------------------------------------------------------------------------------

    @Test
    void anExceptionEscapingTheTaskBodyEndsFailedWithTheMarkerRemovedAndALaterStartIsAccepted() {
        targetIds(1, 2);
        targets.currentFailure = new IllegalStateException("database is down");
        startNow(job);
        drain();

        DatasetStatus failed = job.status();
        assertThat(failed.state()).isEqualTo(State.FAILED);
        assertThat(failed.lastError()).isEqualTo("database is down");
        assertThat(props.marker()).doesNotExist();
        assertThat(queue).isEmpty();

        targets.currentFailure = null;
        DatasetStatus again = startNow(job);
        drain();

        assertThat(again.state()).isEqualTo(State.RUNNING);
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(2);
    }

    @Test
    void anExecutorThatRefusesTheNextTaskEndsFailed() {
        targetIds(1, 2);
        startNow(job);
        executor = task -> {
            throw new RejectedExecutionException("full");
        };

        queue.poll().run();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.lastError()).isEqualTo("full");
        assertThat(status.complete()).isEqualTo(1);
        assertThat(props.marker()).doesNotExist();
    }

    @Test
    void anErrorThatIsNotAnExceptionAlsoEndsTheChainFailedAndNeverEscapes() {
        targetIds(1);
        startNow(job);
        when(refresher.dueNotHeldBack()).thenThrow(new AssertionError("boom")).thenReturn(0);

        queue.poll().run();

        assertThat(job.status().state()).isEqualTo(State.FAILED);
        assertThat(job.status().lastError()).isEqualTo("boom");
    }

    @Test
    void aTaskOfAnOlderRunThatFailsPartWayChangesNoCounterOfTheNewRun() throws IOException {
        targetIds(1, 2, 3);
        startNow(job);
        // Part way through file 2 the owner stops and starts again, and the old task's read then fails.
        onGuardCall = () -> {
            if (guardCalls == 25) {
                job.stop();
                startNow(job);
                throw new IllegalStateException("boom");
            }
        };

        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.DONE);
        assertThat(status.alreadyComplete()).isEqualTo(1);
        assertThat(status.complete()).isEqualTo(2);
        assertThat(status.failed()).isZero();
        assertThat(status.lastError()).isNull();
        assertThat(warnings()).hasSize(1);
        assertThat(frameDirs()).containsExactly("1", "2", "3");
        assertThat(props.marker()).doesNotExist();
    }

    // ---- A stop that lands between two steps of one task ---------------------------------------------------------

    private static Runnable once(Runnable action) {
        boolean[] ran = new boolean[1];
        return () -> {
            if (!ran[0]) {
                ran[0] = true;
                action.run();
            }
        };
    }

    @Test
    void aStopWhileTheRefresherIsBeingAskedStopsTheChainBeforeItYieldsToIt() {
        targetIds(1);
        startNow(job);
        Runnable stopOnce = once(job::stop);
        when(refresher.dueNotHeldBack()).thenAnswer(call -> {
            stopOnce.run();
            return 1;
        });

        drain();

        assertThat(job.status().state()).isEqualTo(State.STOPPED);
        verify(refresher, times(0)).startDue();
        assertThat(queue).isEmpty();
    }

    @Test
    void aStopWhileTheRefresherIsBeingAskedStopsTheChainBeforeItTakesAFile() {
        targetIds(1);
        startNow(job);
        Runnable stopOnce = once(job::stop);
        when(refresher.dueNotHeldBack()).thenAnswer(call -> {
            stopOnce.run();
            return 0;
        });

        drain();

        assertThat(job.status().state()).isEqualTo(State.STOPPED);
        assertThat(job.status().currentMediaFileId()).isNull();
        assertThat(ffmpeg.commands).isEmpty();
    }

    @Test
    void aStopWhileTheDiskIsBeingReadIsNotTurnedIntoNoSpace() {
        targetIds(1);
        startNow(job);
        disk.free = 0;
        disk.duringRead = once(job::stop);

        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.STOPPED);
        assertThat(status.lastError()).isNull();
    }

    @Test
    void aStopThatArrivesAsPlaybackIsFoundBusyLeavesTheRunStoppedNotWaiting() throws IOException {
        targetIds(1, 2);
        busyAtCall = 3;
        onGuardCall = () -> {
            if (guardCalls == 3) {
                job.stop();
            }
        };
        startNow(job);

        drain();

        assertThat(job.status().state()).isEqualTo(State.STOPPED);
        assertThat(job.status().waitingFor()).isNull();
        assertThat(frameDirs()).isEmpty();
        assertThat(queue).isEmpty();
    }

    @Test
    void aStopThatArrivesJustBeforeCompleteJsonIsMovedIntoPlaceLeavesNoCompleteJsonAndNoCounterChanged() {
        targetIds(1, 2);
        Runnable stopOnce = once(job::stop);
        when(git.commit()).thenAnswer(call -> {
            stopOnce.run();
            return "abc";
        });
        startNow(job);

        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.STOPPED);
        assertThat(status.complete()).isZero();
        assertThat(queue).isEmpty();
        // The stale task's move was refused under the monitor, and its directory (tmp file included) is gone.
        assertThat(frames(1)).doesNotExist();
    }

    @Test
    void aResumeTakesANewGenerationSoTheTaskThatSetTheWaitCannotAlsoCarryTheChainOn() {
        targetIds(1, 2);
        busyAtCall = 25;
        startNow(job);
        Runnable last = null;
        while (!queue.isEmpty()) {
            last = queue.poll();
            last.run();
        }
        // The last task is the generation 1 task that set WAITING.
        assertThat(job.status().state()).isEqualTo(State.WAITING);
        int reads = disk.reads;

        last.run();

        assertThat(disk.reads).isEqualTo(reads);
        assertThat(queue).isEmpty();

        job.startIfDeferred();
        last.run();

        // The replayed old task finds a newer generation: nothing is read and only the resume's task is queued.
        assertThat(queue).hasSize(1);
        assertThat(disk.reads).isEqualTo(reads);
        drain();
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(2);
    }

    @Test
    void aQueuedResumeThatRunsAfterAStopDoesNotStartARun() throws IOException {
        targetIds(1);
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        job.onStartup();
        assertThat(queue).hasSize(1);

        job.stop();
        queue.poll().run();

        assertThat(job.status().state()).isEqualTo(State.IDLE);
        assertThat(queue).isEmpty();
        assertThat(targets.listCalls).isZero();
        assertThat(messages()).contains("Credits dataset job: not starting again: stopped or started meanwhile");
    }

    @Test
    void aQueuedResumeThatRunsAfterAManualRunFinishedDoesNotRestartIt() throws IOException {
        targetIds(1);
        Files.createDirectories(props.dir());
        Files.writeString(props.marker(), "a run was going");
        job.onStartup();
        Runnable resume = queue.poll();
        startNow(job);
        drain();
        assertThat(job.status().state()).isEqualTo(State.DONE);

        resume.run();

        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(queue).isEmpty();
        assertThat(targets.listCalls).isEqualTo(1);
    }

    @Test
    void playbackStartingAfterTheLastFfmpegCallDoesNotDiscardAFinishedFile() {
        targetIds(1);
        onGuardCall = () -> {
            if (guardCalls > 16) {
                throw new PlaybackGuard.Busy("a film started");
            }
        };
        startNow(job);

        drain();

        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(1);
        // 5 + 8 + 1 reads and 2 dense writes: no check follows the last of them.
        assertThat(guardCalls).isEqualTo(16);
        assertThat(frames(1).resolve("complete.json")).exists();
    }

    @Test
    void anExceptionThatArrivesAfterAStopDoesNotTurnTheStoppedRunIntoAFailedOne() {
        targetIds(1);
        targets.duringCurrent = once(job::stop);
        targets.currentFailure = new IllegalStateException("database is down");
        startNow(job);

        drain();

        DatasetStatus status = job.status();
        assertThat(status.state()).isEqualTo(State.STOPPED);
        assertThat(status.lastError()).isNull();
    }

    @Test
    void aStopThatArrivesAsAFileIsFoundChangedBeforeItIsReadCountsNothingAndQueuesNothing() {
        targetIds(1, 2);
        targets.changedBeforeRead.add(1L);
        targets.duringCurrent = once(job::stop);
        startNow(job);

        queue.poll().run();

        assertThat(queue).isEmpty();
        assertThat(job.status().skipped()).isZero();
        assertThat(job.status().state()).isEqualTo(State.STOPPED);
    }

    @Test
    void aStopThatArrivesAsAFileIsFoundChangedAfterItWasReadDeletesItsDirectoryCountsNothingAndQueuesNothing() {
        targetIds(1, 2);
        targets.changedAfterRead.add(1L);
        targets.duringCurrent = () -> {
            if (targets.calls.getOrDefault(1L, 0) == 1) {
                job.stop();
            }
        };
        startNow(job);

        queue.poll().run();

        assertThat(queue).isEmpty();
        assertThat(frames(1)).doesNotExist();
        assertThat(job.status().skipped()).isZero();
        assertThat(job.status().state()).isEqualTo(State.STOPPED);
    }

    @Test
    void aStopAfterTheLastReadStopsTheFileBeforeItsCompleteJsonIsWritten() {
        targetIds(1, 2);
        targets.duringCurrent = () -> {
            if (targets.calls.getOrDefault(1L, 0) == 1) {
                job.stop();
            }
        };
        startNow(job);

        queue.poll().run();

        assertThat(queue).isEmpty();
        assertThat(frames(1)).doesNotExist();
        assertThat(job.status().complete()).isZero();
    }

    @Test
    void aDirectoryLeftForTheFileSinceTheStartIsClearedBeforeItIsRead() throws IOException {
        targetIds(1);
        startNow(job);
        Files.createDirectories(frames(1));
        Files.writeString(frames(1).resolve("left-over.txt"), "from an earlier attempt");

        drain();

        assertThat(frames(1).resolve("left-over.txt")).doesNotExist();
        assertThat(frames(1).resolve("complete.json")).exists();
    }

    @Test
    void aDirectoryThatCannotBeDeletedIsLoggedAndTheStartGoesOn() throws IOException {
        targetIds(1);
        Path stale = frames(9);
        Files.createDirectories(stale.resolve("inside"));
        Files.setPosixFilePermissions(stale, PosixFilePermissions.fromString("---------"));
        try {
            DatasetStatus status = startNow(job);

            assertThat(status.state()).isEqualTo(State.RUNNING);
            // Run as root the permissions do not bind and the delete simply works.
            if (Files.exists(stale)) {
                assertThat(warnings()).hasSize(1);
                assertThat(warnings().getFirst()).startsWith("Could not remove a dataset directory: ");
            }
        } finally {
            if (Files.exists(stale)) {
                Files.setPosixFilePermissions(stale,
                    PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    // ---- Yielding to the credits detection -----------------------------------------------------------------------

    @Test
    void theFirstTimeTheRefresherHasDueFilesTheJobStartsItAndGoesOnAfterItsWorker() {
        targetIds(1);
        startNow(job);
        dueScript.addAll(List.of(1, 0));

        queue.poll().run();

        verify(refresher).startDue();
        assertThat(job.status().state()).isEqualTo(State.RUNNING);
        assertThat(queue).hasSize(1);
        assertThat(ffmpeg.commands).isEmpty();
        drain();
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(1);
    }

    @Test
    void theSecondTimeInARowTheJobWaitsForTheRefresherKeepingTheMarkerAndResumesWithAFreshYield()
        throws IOException {
        targetIds(1);
        startNow(job);
        dueScript.addAll(List.of(1, 1));

        drain();

        DatasetStatus waiting = job.status();
        assertThat(waiting.state()).isEqualTo(State.WAITING);
        assertThat(waiting.waitingFor()).isEqualTo(Waiting.REFRESHER);
        assertThat(waiting.currentMediaFileId()).isNull();
        verify(refresher, times(1)).startDue();
        assertThat(props.marker()).exists();
        assertThat(queue).isEmpty();

        // Resumed by the hourly nudge, the count of yields in a row starts again: it asks the refresher once more.
        dueScript.addAll(List.of(1, 0));
        job.startIfDeferred();
        drain();

        verify(refresher, times(2)).startDue();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void processingAFileResetsTheCountOfYieldsInARow() {
        targetIds(1, 2);
        startNow(job);
        // yield, read file 1, yield again (not a wait: a file was done in between), read file 2.
        dueScript.addAll(List.of(1, 0, 1, 0));

        drain();

        verify(refresher, times(2)).startDue();
        assertThat(job.status().state()).isEqualTo(State.DONE);
        assertThat(job.status().complete()).isEqualTo(2);
    }

    @Test
    void aSkippedFileAlsoResetsTheCountOfYieldsInARow() {
        targetIds(1, 2);
        targets.changedBeforeRead.add(1L);
        startNow(job);
        dueScript.addAll(List.of(1, 0, 1, 0));

        drain();

        verify(refresher, times(2)).startDue();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void aFailedFileAlsoResetsTheCountOfYieldsInARow() {
        targetIds(1, 2);
        unresolvable.add("f1.m4v");
        startNow(job);
        dueScript.addAll(List.of(1, 0, 1, 0));

        drain();

        verify(refresher, times(2)).startDue();
        assertThat(job.status().state()).isEqualTo(State.DONE);
    }

    @Test
    void aStopWhileTheRefresherIsBeingAwaitedEndsTheChain() {
        targetIds(1);
        startNow(job);
        dueScript.addAll(List.of(1));
        queue.poll().run();
        job.stop();
        dueScript.addAll(List.of(1));

        drain();

        assertThat(job.status().state()).isEqualTo(State.STOPPED);
        assertThat(ffmpeg.commands).isEmpty();
    }

    /** One real refresher (its service mocked) on the same queue as the job: its worker runs between two files. */
    @Test
    void aRefresherWorkerQueuedWhileTheDatasetRunsRunsBetweenTwoDatasetFilesOnTheSameThread() {
        CreditsDetectionService service = mock(CreditsDetectionService.class);
        DueFile due = new DueFile(900L, "new.m4v", "1:1", 6_000_000L, "New");
        boolean[] detected = new boolean[1];
        when(service.dueFiles()).thenAnswer(call -> !ffmpeg.commands.isEmpty() && !detected[0] ? List.of(due)
            : List.of());
        CreditsResult found = new CreditsResult(CreditsVerdict.NOT_FOUND, null, List.of("None"));
        doAnswer(call -> {
            detected[0] = true;
            return found;
        }).when(service).detect(eq(due), eq(Path.of("/library/new.m4v")), any(Runnable.class));
        when(service.save(due, found, NOW)).thenReturn(SaveOutcome.SAVED);
        CreditsFrameReader unusedReader = mock(CreditsFrameReader.class);
        CreditsRefresher real = new CreditsRefresher(service, unusedReader, root, guard, CLOCK, queue::add, true);
        CreditsFrameReader reader = new CreditsFrameReader(runner, "ffmpeg", 10, temp.resolve("cache").toString());
        CreditsDatasetJob sharing = new CreditsDatasetJob(targets, reader, root, guard, real, runner, git, disk, props,
            CLOCK, queue::add);
        targetIds(1, 2);

        startNow(sharing);
        queue.poll().run();
        // File 1 is read and the first file's task queued the next.
        assertThat(ffmpeg.commands).hasSize(5 + 8 + 1);
        assertThat(queue).hasSize(1);

        queue.poll().run();
        // The next task saw the refresher's due file, queued its worker, and queued itself behind it.
        assertThat(queue).hasSize(2);
        assertThat(ffmpeg.commands).hasSize(5 + 8 + 1);
        assertThat(detected[0]).isFalse();

        queue.poll().run();
        // The refresher's worker ran before the dataset's second file was read.
        assertThat(detected[0]).isTrue();
        assertThat(ffmpeg.commands).hasSize(5 + 8 + 1);
        assertThat(queue).hasSize(1);

        drain();
        assertThat(sharing.status().state()).isEqualTo(State.DONE);
        assertThat(sharing.status().complete()).isEqualTo(2);
        verify(service).save(due, found, NOW);
    }
}
