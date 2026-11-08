package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real {@link ProcessCommandRunner} against POSIX tools every CI and dev machine has. */
class ProcessCommandRunnerTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @Test
    void capturesStandardOutputAndAZeroExit() throws IOException {
        CommandRunner.Result result = runner.run(List.of("/bin/echo", "hello"), Duration.ofSeconds(10));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello\n");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void anArgumentIsNeverInterpretedByAShell() throws IOException {
        String hostile = "a; echo injected $(echo pwned) `id` > /tmp/x";

        CommandRunner.Result result = runner.run(List.of("/bin/echo", hostile), Duration.ofSeconds(10));

        assertThat(result.stdout()).isEqualTo(hostile + "\n");
    }

    @Test
    void aNonZeroExitIsReportedAndIsNotSuccess() throws IOException {
        CommandRunner.Result result = runner.run(List.of("/bin/sh", "-c", "echo partial; exit 3"),
            Duration.ofSeconds(10));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEqualTo("partial\n");
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void aProcessThatRunsPastTheTimeoutIsKilledAndReportedAsTimedOut() throws IOException {
        long started = System.nanoTime();

        CommandRunner.Result result = runner.run(List.of("/bin/sleep", "30"), Duration.ofMillis(300));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.succeeded()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void aProgramThatIsNotInstalledIsAnIoException() {
        assertThatThrownBy(() -> runner.run(List.of("/nonexistent/ffprobe-xyz"), Duration.ofSeconds(1)))
            .isInstanceOf(IOException.class);
    }

    @Test
    void standardInputIsClosedSoAToolNeverWaitsForIt() throws IOException {
        CommandRunner.Result result = runner.run(List.of("/bin/cat"), Duration.ofSeconds(10));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void outputBeyondTheCapIsDroppedRatherThanBuffered() throws IOException {
        CommandRunner.Result result = runner.run(
            List.of("/bin/sh", "-c", "head -c " + (ProcessCommandRunner.MAX_STDOUT_BYTES + 1000) + " /dev/zero"),
            Duration.ofSeconds(20));

        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).hasSize(ProcessCommandRunner.MAX_STDOUT_BYTES);
    }

    @Test
    void aThreadInterruptedWhileWaitingKillsTheProcessAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runner.run(List.of("/bin/sleep", "30"), Duration.ofSeconds(30)))
                .isInstanceOf(IOException.class).hasMessage("Interrupted while waiting for /bin/sleep");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
