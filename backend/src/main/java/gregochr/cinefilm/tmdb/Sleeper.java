package gregochr.cinefilm.tmdb;

import java.time.Duration;

/** Waits. A seam so the TMDB client's backoff and pacing are testable without real time. */
@FunctionalInterface
public interface Sleeper {

    /** The real thing: blocks the calling thread. */
    Sleeper THREAD = duration -> Thread.sleep(duration);

    /**
     * Blocks for the duration.
     *
     * @param duration how long to wait
     * @throws InterruptedException when the thread is interrupted
     */
    void sleep(Duration duration) throws InterruptedException;
}
