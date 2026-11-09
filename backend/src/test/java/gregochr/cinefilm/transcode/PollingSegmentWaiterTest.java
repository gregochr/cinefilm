package gregochr.cinefilm.transcode;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.transcode.SessionFakes.MutableClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The poll is one millisecond here; the clock only moves when the condition moves it, so no test depends on timing. */
class PollingSegmentWaiterTest {

    private final MutableClock clock = new MutableClock();
    private final PollingSegmentWaiter waiter = new PollingSegmentWaiter(clock, Duration.ofMillis(1));

    @Test
    void aConditionThatIsAlreadyTrueReturnsAfterOneCheck() {
        AtomicInteger checks = new AtomicInteger();

        waiter.await(() -> {
            checks.incrementAndGet();
            return true;
        }, Duration.ofSeconds(6));

        assertThat(checks).hasValue(1);
    }

    @Test
    void theConditionIsPolledUntilItBecomesTrue() {
        AtomicInteger checks = new AtomicInteger();

        waiter.await(() -> checks.incrementAndGet() == 3, Duration.ofSeconds(6));

        assertThat(checks).hasValue(3);
    }

    @Test
    void aConditionThatNeverHoldsStopsOnceTheTimeoutHasPassed() {
        AtomicInteger checks = new AtomicInteger();

        waiter.await(() -> {
            checks.incrementAndGet();
            clock.advance(Duration.ofSeconds(2));
            return false;
        }, Duration.ofSeconds(6));

        assertThat(checks).hasValue(3);
    }

    @Test
    void anInterruptEndsTheWaitAndKeepsTheInterruptFlag() {
        Thread.currentThread().interrupt();

        waiter.await(() -> false, Duration.ofSeconds(6));

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void theProductionConstructorPollsEveryHundredMilliseconds() {
        assertThat(PollingSegmentWaiter.POLL).isEqualTo(Duration.ofMillis(100));
        new PollingSegmentWaiter(clock).await(() -> true, Duration.ofSeconds(1));
    }
}
