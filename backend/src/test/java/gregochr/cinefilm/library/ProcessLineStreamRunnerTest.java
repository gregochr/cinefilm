package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real {@link ProcessLineStreamRunner} against POSIX tools every CI and dev machine has. */
class ProcessLineStreamRunnerTest {

    private final ProcessLineStreamRunner runner = new ProcessLineStreamRunner();
    private final List<String> lines = new ArrayList<>();

    @Test
    void handsOverEachLineWithoutItsTerminatorAndReportsAZeroExit() throws IOException {
        LineStreamRunner.Outcome outcome = runner.run(List.of("/bin/sh", "-c", "echo one; echo two; printf last"),
            Duration.ofSeconds(10), lines::add);

        assertThat(lines).containsExactly("one", "two", "last");
        assertThat(outcome).isEqualTo(new LineStreamRunner.Outcome(0, false));
        assertThat(outcome.succeeded()).isTrue();
    }

    @Test
    void anArgumentIsNeverInterpretedByAShell() throws IOException {
        String hostile = "a; echo injected $(echo pwned) `id`";

        runner.run(List.of("/bin/echo", hostile), Duration.ofSeconds(10), lines::add);

        assertThat(lines).containsExactly(hostile);
    }

    @Test
    void aNonZeroExitIsReportedAndIsNotSuccess() throws IOException {
        LineStreamRunner.Outcome outcome = runner.run(List.of("/bin/sh", "-c", "echo partial; exit 3"),
            Duration.ofSeconds(10), lines::add);

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.timedOut()).isFalse();
        assertThat(outcome.succeeded()).isFalse();
        assertThat(lines).containsExactly("partial");
    }

    @Test
    void aProcessThatRunsPastTheTimeoutIsKilledAndReportedAsTimedOutKeepingTheLinesItWrote() throws IOException {
        long started = System.nanoTime();

        LineStreamRunner.Outcome outcome = runner.run(List.of("/bin/sh", "-c", "echo early; exec sleep 30"),
            Duration.ofMillis(500), lines::add);

        assertThat(outcome).isEqualTo(new LineStreamRunner.Outcome(-1, true));
        assertThat(outcome.succeeded()).isFalse();
        assertThat(lines).containsExactly("early");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void outputIsStreamedNotBufferedSoALargeOutputReachesTheHandlerLineByLine() throws IOException {
        long[] count = {0};

        LineStreamRunner.Outcome outcome = runner.run(List.of("/bin/sh", "-c", "yes 0.000000,K_ | head -n 200000"),
            Duration.ofSeconds(30), line -> count[0]++);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(count[0]).isEqualTo(200_000);
    }

    @Test
    void standardInputIsClosedSoAToolNeverWaitsForIt() throws IOException {
        LineStreamRunner.Outcome outcome = runner.run(List.of("/bin/cat"), Duration.ofSeconds(10), lines::add);

        assertThat(outcome.succeeded()).isTrue();
        assertThat(lines).isEmpty();
    }

    @Test
    void aProgramThatIsNotInstalledIsAnIoException() {
        assertThatThrownBy(() -> runner.run(List.of("/nonexistent/ffprobe-xyz"), Duration.ofSeconds(1), lines::add))
            .isInstanceOf(IOException.class);
    }

    @Test
    void aHandlerThatThrowsStopsTheRunAndKillsTheProcess() {
        assertThatThrownBy(() -> runner.run(List.of("/bin/sh", "-c", "echo x; exec sleep 30"),
            Duration.ofSeconds(30), line -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void anInterruptedWaitIsAnIoExceptionAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> runner.run(List.of("/bin/echo", "hi"), Duration.ofSeconds(30),
                lines::add)).isInstanceOf(IOException.class).hasMessageContaining("Interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
