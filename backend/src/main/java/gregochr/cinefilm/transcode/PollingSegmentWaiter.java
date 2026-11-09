package gregochr.cinefilm.transcode;

import java.time.Clock;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** {@link SegmentWaiter} that polls the condition on the calling (virtual) thread. */
@Component
class PollingSegmentWaiter implements SegmentWaiter {

    /** How often the condition is checked. */
    static final Duration POLL = Duration.ofMillis(100);

    private final Clock clock;
    private final long pollMillis;

    @Autowired
    PollingSegmentWaiter(Clock clock) {
        this(clock, POLL);
    }

    PollingSegmentWaiter(Clock clock, Duration poll) {
        this.clock = clock;
        this.pollMillis = poll.toMillis();
    }

    @Override
    public void await(BooleanSupplier condition, Duration timeout) {
        long deadline = clock.millis() + timeout.toMillis();
        try {
            while (!condition.getAsBoolean() && clock.millis() < deadline) {
                Thread.sleep(pollMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
