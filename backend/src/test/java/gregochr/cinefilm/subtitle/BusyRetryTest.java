package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The bounded retry of a step the library lock refused: the pause is injected, so nothing waits. */
class BusyRetryTest {

    private final List<Duration> pauses = new ArrayList<>();
    private final BusyRetry retry = new BusyRetry(pauses::add);

    @Test
    void aStepThatSettlesAtOnceIsNeverPausedFor() {
        assertThat(retry.until("step", () -> true)).isTrue();

        assertThat(pauses).isEmpty();
    }

    @Test
    void aBusyStepIsTriedAgainAfterAPauseAndSettles() {
        AtomicInteger tries = new AtomicInteger();

        assertThat(retry.until("step", () -> tries.incrementAndGet() == 3)).isTrue();

        assertThat(tries).hasValue(3);
        assertThat(pauses).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void aStepThatSettlesOnTheLastTryIsSettled() {
        AtomicInteger tries = new AtomicInteger();

        assertThat(retry.until("step", () -> tries.incrementAndGet() == BusyRetry.ATTEMPTS)).isTrue();

        assertThat(pauses).hasSize(BusyRetry.ATTEMPTS - 1);
    }

    @Test
    void aStepThatIsAlwaysBusyGivesUpAfterTheBoundWithAPauseBetweenTriesOnly() {
        AtomicInteger tries = new AtomicInteger();

        assertThat(retry.until("step", () -> {
            tries.incrementAndGet();
            return false;
        })).isFalse();

        assertThat(tries).hasValue(BusyRetry.ATTEMPTS);
        assertThat(pauses).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    @Test
    void anInterruptedPauseGivesUpAtOnceAndKeepsTheInterrupt() {
        BusyRetry interrupted = new BusyRetry(pause -> {
            throw new InterruptedException("stop");
        });
        AtomicInteger tries = new AtomicInteger();

        try {
            assertThat(interrupted.until("step", () -> {
                tries.incrementAndGet();
                return false;
            })).isFalse();

            assertThat(tries).hasValue(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clears the flag, so the interrupt does not leak into the next test.
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
