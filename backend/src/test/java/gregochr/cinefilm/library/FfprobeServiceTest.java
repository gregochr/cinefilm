package gregochr.cinefilm.library;

import static gregochr.cinefilm.TestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FfprobeServiceTest {

    private static final Path FILE = Path.of("/media/10 Cloverfield Lane; rm -rf $HOME.m4v");
    private static final String JSON = "{\"format\":{\"format_name\":\"mov,mp4\",\"duration\":\"90.5\"},"
        + "\"streams\":[]}";

    private final CommandRunner runner = mock(CommandRunner.class);
    private final FfprobeService service = new FfprobeService(runner, "ffprobe", CLOCK);

    private static boolean await(CountDownLatch latch) throws IOException {
        try {
            return latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @Test
    void theCommandIsAnArgumentListWithTheFileAsOneLastElement() {
        assertThat(FfprobeService.command("/usr/bin/ffprobe", FILE)).containsExactly("/usr/bin/ffprobe", "-v", "error",
            "-print_format", "json", "-show_format", "-show_streams", FILE.toString());
    }

    @Test
    void probesWithTheSixtySecondTimeoutAndParsesTheOutput() throws IOException {
        when(runner.run(FfprobeService.command("ffprobe", FILE), Duration.ofSeconds(60)))
            .thenReturn(new CommandRunner.Result(0, JSON, false));

        assertThat(service.probe(FILE).durationMs()).isEqualTo(90_500L);
        assertThat(FfprobeService.TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aTimeoutIsAProbeException() throws IOException {
        when(runner.run(FfprobeService.command("ffprobe", FILE), FfprobeService.TIMEOUT))
            .thenReturn(new CommandRunner.Result(-1, "", true));

        assertThatThrownBy(() -> service.probe(FILE)).isInstanceOf(ProbeException.class)
            .hasMessage("ffprobe timed out after 60 seconds");
    }

    @Test
    void aNonZeroExitIsAProbeExceptionWithTheStatus() throws IOException {
        when(runner.run(FfprobeService.command("ffprobe", FILE), FfprobeService.TIMEOUT))
            .thenReturn(new CommandRunner.Result(1, "", false));

        assertThatThrownBy(() -> service.probe(FILE)).isInstanceOf(ProbeException.class)
            .hasMessage("ffprobe exited with status 1");
    }

    @Test
    void aMissingToolIsAProbeException() throws IOException {
        when(runner.run(FfprobeService.command("ffprobe", FILE), FfprobeService.TIMEOUT))
            .thenThrow(new IOException("Cannot run program \"ffprobe\""));

        assertThatThrownBy(() -> service.probe(FILE)).isInstanceOf(ProbeException.class)
            .hasMessage("ffprobe could not be run: Cannot run program \"ffprobe\"");
    }

    @Test
    void unusableOutputIsAProbeException() throws IOException {
        when(runner.run(FfprobeService.command("ffprobe", FILE), FfprobeService.TIMEOUT))
            .thenReturn(new CommandRunner.Result(0, "garbage", false));

        assertThatThrownBy(() -> service.probe(FILE)).isInstanceOf(ProbeException.class);
    }

    @Test
    void atMostTwoProbesRunAtOnce() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch twoStarted = new CountDownLatch(FfprobeService.MAX_CONCURRENT);
        CountDownLatch release = new CountDownLatch(1);
        CommandRunner blocking = (command, timeout) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            twoStarted.countDown();
            try {
                assertThat(await(release)).isTrue();
            } finally {
                running.decrementAndGet();
            }
            return new CommandRunner.Result(0, JSON, false);
        };
        FfprobeService limited = new FfprobeService(blocking, "ffprobe", CLOCK);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Future<ProbeResult>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(pool.submit(() -> limited.probe(FILE)));
            }
            assertThat(twoStarted.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(running.get()).isEqualTo(2);
            release.countDown();
            for (var result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS).durationMs()).isEqualTo(90_500L);
            }
            assertThat(peak.get()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aThreadInterruptedWhileWaitingForASlotGivesUpWithoutRunningFfprobe() throws Exception {
        CountDownLatch occupied = new CountDownLatch(FfprobeService.MAX_CONCURRENT);
        CountDownLatch release = new CountDownLatch(1);
        CommandRunner blocking = (command, timeout) -> {
            occupied.countDown();
            await(release);
            return new CommandRunner.Result(0, JSON, false);
        };
        FfprobeService limited = new FfprobeService(blocking, "ffprobe", CLOCK);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> limited.probe(FILE));
            pool.submit(() -> limited.probe(FILE));
            assertThat(occupied.await(20, TimeUnit.SECONDS)).isTrue();
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> limited.probe(FILE)).isInstanceOf(ProbeException.class)
                .hasMessage("Interrupted before probing");
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void theSlotIsReleasedAfterAFailure() throws IOException {
        when(runner.run(FfprobeService.command("ffprobe", FILE), FfprobeService.TIMEOUT))
            .thenReturn(new CommandRunner.Result(1, "", false));

        for (int i = 0; i < FfprobeService.MAX_CONCURRENT + 2; i++) {
            assertThatThrownBy(() -> service.probe(FILE)).isInstanceOf(ProbeException.class);
        }
        ArgumentCaptor<List<String>> command = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(runner, org.mockito.Mockito.times(FfprobeService.MAX_CONCURRENT + 2))
            .run(command.capture(), org.mockito.ArgumentMatchers.eq(FfprobeService.TIMEOUT));
        assertThat(command.getValue()).last().isEqualTo(FILE.toString());
    }

    @Test
    void aReleaseYearIsBelievedUpToTheYearAfterTheClocksYear() throws IOException {
        String upTo = "{\"format\":{\"format_name\":\"mp4\",\"tags\":{\"date\":\"2025\"}},\"streams\":[]}";
        String past = "{\"format\":{\"format_name\":\"mp4\",\"tags\":{\"date\":\"2026\"}},\"streams\":[]}";
        when(runner.run(FfprobeService.command("ffprobe", FILE), FfprobeService.TIMEOUT))
            .thenReturn(new CommandRunner.Result(0, upTo, false), new CommandRunner.Result(0, past, false));

        assertThat(service.probe(FILE).tags().year()).isEqualTo(2025);
        assertThat(service.probe(FILE).tags().year()).isNull();
    }
}
