package gregochr.cinefilm.tmdb;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Spaces requests at least a fixed interval apart. TMDB allows about 40 per second and we keep to 20; other clients
 * (OpenSubtitles, 5 per second) pass their own interval.
 */
public final class RequestPacer {

    /** 20 requests per second. */
    static final Duration INTERVAL = Duration.ofMillis(50);

    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final long intervalNanos;
    private long nextAllowedNanos;

    RequestPacer(LongSupplier nanoClock, Sleeper sleeper) {
        this(nanoClock, sleeper, INTERVAL);
    }

    /** Creates a pacer that lets one request through per {@code interval}. */
    public RequestPacer(LongSupplier nanoClock, Sleeper sleeper, Duration interval) {
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.intervalNanos = interval.toNanos();
        this.nextAllowedNanos = nanoClock.getAsLong();
    }

    /**
     * Blocks until a request may be sent, then reserves the slot.
     *
     * @throws InterruptedException when the thread is interrupted while waiting
     */
    public synchronized void await() throws InterruptedException {
        long now = nanoClock.getAsLong();
        long wait = nextAllowedNanos - now;
        if (wait > 0) {
            sleeper.sleep(Duration.ofNanos(wait));
            now = nextAllowedNanos;
        }
        nextAllowedNanos = now + intervalNanos;
    }
}
