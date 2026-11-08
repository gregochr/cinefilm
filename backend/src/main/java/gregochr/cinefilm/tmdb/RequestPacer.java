package gregochr.cinefilm.tmdb;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Spaces requests at least a fixed interval apart (TMDB allows about 40 per second; we keep to 20). */
final class RequestPacer {

    /** 20 requests per second. */
    static final Duration INTERVAL = Duration.ofMillis(50);

    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private long nextAllowedNanos;

    RequestPacer(LongSupplier nanoClock, Sleeper sleeper) {
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.nextAllowedNanos = nanoClock.getAsLong();
    }

    /** Blocks until a request may be sent, then reserves the slot. */
    synchronized void await() throws InterruptedException {
        long now = nanoClock.getAsLong();
        long wait = nextAllowedNanos - now;
        if (wait > 0) {
            sleeper.sleep(Duration.ofNanos(wait));
            now = nextAllowedNanos;
        }
        nextAllowedNanos = now + INTERVAL.toNanos();
    }
}
