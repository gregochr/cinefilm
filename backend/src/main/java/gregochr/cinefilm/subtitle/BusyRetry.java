package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.tmdb.Sleeper;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tries a step again while the library lock is taken by a scan or an Admin edit, a bounded number of times with a pause
 * that doubles (docs/engineering/subtitle-frame-rate-plan.md, "The re-check runs at once"). The pause is the injected
 * {@link Sleeper}, so tests never wait.
 */
@Component
public class BusyRetry {

    private static final Logger LOG = LoggerFactory.getLogger(BusyRetry.class);

    /** How many times a step is tried in all. */
    static final int ATTEMPTS = 4;
    /** The pause before the second try; each later one is twice the one before. */
    static final Duration FIRST_PAUSE = Duration.ofSeconds(5);

    private final Sleeper sleeper;

    /** Creates the helper. */
    public BusyRetry(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    /**
     * Tries a step until it is settled or the tries run out.
     *
     * @param what what the step does, for the log
     * @param attempt the step; true when it is settled (done, or nothing more to do), false when the lock was busy
     * @return true when it settled; false when every try was busy (or the thread was interrupted): leave it due
     */
    public boolean until(String what, BooleanSupplier attempt) {
        Duration pause = FIRST_PAUSE;
        for (int tried = 1; tried < ATTEMPTS; tried++) {
            if (attempt.getAsBoolean()) {
                return true;
            }
            try {
                sleeper.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            pause = pause.multipliedBy(2);
        }
        if (attempt.getAsBoolean()) {
            return true;
        }
        LOG.info("{}: the library stayed busy, left for the next run", what);
        return false;
    }
}
