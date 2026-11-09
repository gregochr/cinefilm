package gregochr.cinefilm.transcode;

import java.time.Duration;

/** Runs the session manager's periodic upkeep. An interface so tests drive it by hand. */
interface SessionScheduler {

    /**
     * Runs the task repeatedly, one run at a time, with the period between the end of one run and the start of the
     * next. A run that throws does not stop later runs.
     *
     * @param period the delay between runs
     * @param task what to run
     * @return call it to stop the repetition
     */
    Runnable scheduleEvery(Duration period, Runnable task);
}
