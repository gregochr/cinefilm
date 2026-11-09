package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Runs the real adapter over {@code sh}: progress parsing, the stderr tail, the exit report, stop and the factory. */
class ProcessEncoderProcessTest {

    private static final Duration GRACE = Duration.ofMillis(300);

    /** Records what the listener is told; {@link #await()} blocks until the exit report arrives. */
    private static final class Recorder implements EncoderListener {
        private final List<EncoderProgress> progress = new CopyOnWriteArrayList<>();
        private final CountDownLatch exited = new CountDownLatch(1);
        private final CountDownLatch reported = new CountDownLatch(1);
        private final AtomicInteger code = new AtomicInteger(-99);
        private final AtomicReference<String> tail = new AtomicReference<>();
        private final AtomicInteger exits = new AtomicInteger();

        @Override
        public void onProgress(EncoderProgress report) {
            progress.add(report);
            reported.countDown();
        }

        @Override
        public void onExit(int exitCode, String logTail) {
            code.set(exitCode);
            tail.set(logTail);
            exits.incrementAndGet();
            exited.countDown();
        }

        private void awaitProgress() throws InterruptedException {
            assertThat(reported.await(30, TimeUnit.SECONDS)).as("a progress report").isTrue();
        }

        private void await() throws InterruptedException {
            assertThat(exited.await(30, TimeUnit.SECONDS)).as("the process exited").isTrue();
        }
    }

    private static List<String> sh(String script) {
        return List.of("sh", "-c", script);
    }

    @Test
    void progressBlocksStderrAndTheExitCodeAreReportedInOrder() throws Exception {
        Recorder recorder = new Recorder();

        EncoderProcess process = ProcessEncoderProcess.start(sh(
            "echo out_time_us=1000000; echo speed=2.0x; echo progress=continue;"
                + " echo out_time_us=2500000; echo progress=end; echo oops >&2; exit 3"), recorder, GRACE);
        recorder.await();

        assertThat(recorder.progress).containsExactly(new EncoderProgress(1000, 2.0), new EncoderProgress(2500, 2.0));
        assertThat(recorder.code.get()).isEqualTo(3);
        assertThat(recorder.tail.get()).isEqualTo("oops");
        assertThat(recorder.exits.get()).isEqualTo(1);
        assertThat(process.isAlive()).isFalse();
        assertThat(process.exitCode()).hasValue(3);
    }

    @Test
    void aRunningProcessIsAliveWithNoExitCodeAndStopEndsIt() throws Exception {
        Recorder recorder = new Recorder();
        EncoderProcess process = ProcessEncoderProcess.start(sh("exec sleep 30"), recorder, GRACE);

        assertThat(process.isAlive()).isTrue();
        assertThat(process.exitCode()).isEmpty();
        process.stop();
        recorder.await();

        assertThat(process.isAlive()).isFalse();
        assertThat(recorder.code.get()).isEqualTo(143);
    }

    @Test
    void aProcessThatIgnoresTermIsKilledAfterTheGracePeriod() throws Exception {
        Recorder recorder = new Recorder();
        EncoderProcess process = ProcessEncoderProcess.start(sh("trap '' TERM; echo progress=continue; exec sleep 30"),
            recorder, GRACE);
        recorder.awaitProgress();

        process.stop();
        recorder.await();

        assertThat(process.isAlive()).isFalse();
        assertThat(recorder.code.get()).isEqualTo(137);
    }

    @Test
    void anInterruptedStopStillKillsTheProcessAndKeepsTheInterruptFlag() throws Exception {
        Recorder recorder = new Recorder();
        EncoderProcess process = ProcessEncoderProcess.start(sh("trap '' TERM; echo progress=continue; exec sleep 30"),
            recorder, Duration.ofSeconds(30));
        recorder.awaitProgress();

        Thread.currentThread().interrupt();
        process.stop();
        boolean interrupted = Thread.interrupted();
        recorder.await();

        assertThat(interrupted).isTrue();
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void aProgramThatDoesNotExistCannotBeStarted() {
        assertThatThrownBy(() -> ProcessEncoderProcess.start(List.of("/no/such/ffmpeg"), new Recorder(), GRACE))
            .isInstanceOf(IOException.class);
    }

    @Test
    void theFactoryWrapsCommandsInNiceAndSkipsTheWrapperWhenNotPositive() {
        ProcessEncoderFactory nice = new ProcessEncoderFactory(10);

        assertThat(nice.wrap(List.of("ffmpeg", "-i", "x"))).containsExactly("nice", "-n", "10", "ffmpeg", "-i", "x");
        assertThat(new ProcessEncoderFactory(0, GRACE).wrap(List.of("ffmpeg"))).containsExactly("ffmpeg");
        assertThat(new ProcessEncoderFactory(-5, GRACE).wrap(List.of("ffmpeg"))).containsExactly("ffmpeg");
    }

    @Test
    void theFactoryStartsRealProcessesAndShutdownStopsTheLiveOnes() throws Exception {
        ProcessEncoderFactory factory = new ProcessEncoderFactory(0, GRACE);
        Recorder finished = new Recorder();
        Recorder running = new Recorder();

        EncoderProcess quick = factory.start(sh("echo progress=end"), finished);
        finished.await();
        EncoderProcess slow = factory.start(sh("exec sleep 30"), running);
        factory.shutdown();
        running.await();

        assertThat(finished.progress).containsExactly(new EncoderProgress(0, 0));
        assertThat(quick.isAlive()).isFalse();
        assertThat(slow.isAlive()).isFalse();
    }

    @Test
    void theNiceWrapperRunsARealCommand() throws Exception {
        Recorder recorder = new Recorder();

        new ProcessEncoderFactory(5, GRACE).start(sh("echo out_time_us=7000; echo progress=end"), recorder);
        recorder.await();

        assertThat(recorder.progress).containsExactly(new EncoderProgress(7, 0));
        assertThat(recorder.code.get()).isZero();
    }

    @Test
    void shuttingDownAFactoryWithNothingRunningDoesNothing() {
        new ProcessEncoderFactory(0, GRACE).shutdown();
    }

    @Test
    void aStreamThatFailsMidReadEndsTheReaderQuietlyAfterTheLinesItGave() {
        java.io.InputStream failing = new java.io.InputStream() {
            private int n;

            @Override
            public int read() throws IOException {
                if (n < 3) {
                    return "a\n".charAt(n++ % 2);
                }
                throw new IOException("Stream closed");
            }
        };
        List<String> lines = new java.util.ArrayList<>();

        ProcessEncoderProcess.forEachLine(failing, lines::add);

        assertThat(lines).containsExactly("a");
    }

    @Test
    void anInterruptedWaitKillsTheProcessAndReportsNoExit() throws Exception {
        Process sleeper = new ProcessBuilder("sleep", "30").start();
        Thread idle = Thread.ofVirtual().unstarted(() -> { });
        Recorder recorder = new Recorder();

        Thread.currentThread().interrupt();
        ProcessEncoderProcess.awaitExit(sleeper, idle, idle, new LogTail(), recorder);
        boolean interrupted = Thread.interrupted();

        assertThat(interrupted).isTrue();
        assertThat(sleeper.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(recorder.exits.get()).isZero();
    }

    @Test
    void startingAnotherEncoderKeepsTheOnesStillRunningUntilShutdown() throws Exception {
        ProcessEncoderFactory factory = new ProcessEncoderFactory(0, GRACE);
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        EncoderProcess one = factory.start(sh("exec sleep 30"), first);
        EncoderProcess two = factory.start(sh("exec sleep 30"), second);
        assertThat(one.isAlive()).isTrue();
        factory.shutdown();
        first.await();
        second.await();

        assertThat(one.isAlive()).isFalse();
        assertThat(two.isAlive()).isFalse();
    }

    @Test
    void theExitIsReportedOnlyAfterTheProgressReaderHasFinished() throws Exception {
        Process finished = new ProcessBuilder("true").start();
        finished.waitFor();
        CountDownLatch releaseReader = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean readerDone = new java.util.concurrent.atomic.AtomicBoolean();
        Thread slowReader = Thread.ofVirtual().start(() -> {
            try {
                releaseReader.await();
                readerDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread errors = Thread.ofVirtual().start(() -> { });
        CountDownLatch exitReported = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean readerDoneAtExit = new java.util.concurrent.atomic.AtomicBoolean();
        EncoderListener listener = new EncoderListener() {
            @Override
            public void onProgress(EncoderProgress report) {
            }

            @Override
            public void onExit(int exitCode, String logTail) {
                readerDoneAtExit.set(readerDone.get());
                exitReported.countDown();
            }
        };

        Thread.ofVirtual().start(() ->
            ProcessEncoderProcess.awaitExit(finished, slowReader, errors, new LogTail(), listener));
        assertThat(exitReported.getCount()).as("exit not reported while the reader is blocked").isEqualTo(1);
        releaseReader.countDown();

        assertThat(exitReported.await(30, TimeUnit.SECONDS)).as("exit reported after release").isTrue();
        assertThat(readerDoneAtExit).as("reader had finished when the exit was reported").isTrue();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Set<EncoderProcess> live(ProcessEncoderFactory factory) throws Exception {
        java.lang.reflect.Field field = ProcessEncoderFactory.class.getDeclaredField("live");
        field.setAccessible(true);
        return (java.util.Set<EncoderProcess>) field.get(factory);
    }

    @Test
    void theFactoryForgetsFinishedEncodersWhenStartingTheNextAndEveryoneOnShutdown() throws Exception {
        ProcessEncoderFactory factory = new ProcessEncoderFactory(0, GRACE);
        Recorder finished = new Recorder();
        Recorder running = new Recorder();

        factory.start(sh("echo progress=end"), finished);
        finished.await();
        assertThat(live(factory)).hasSize(1);
        EncoderProcess slow = factory.start(sh("exec sleep 30"), running);

        assertThat(live(factory)).containsExactly(slow);
        factory.shutdown();
        running.await();
        assertThat(live(factory)).isEmpty();
    }
}
