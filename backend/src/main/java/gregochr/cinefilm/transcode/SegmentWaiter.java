package gregochr.cinefilm.transcode;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Waits for a segment to appear. An interface so tests script what happens while it waits, without sleeping. */
interface SegmentWaiter {

    /**
     * Returns as soon as the condition is true, or after the timeout, whichever comes first. The condition is
     * checked at least once.
     *
     * @param condition what to wait for; cheap and safe to call from any thread
     * @param timeout the longest to wait
     */
    void await(BooleanSupplier condition, Duration timeout);
}
