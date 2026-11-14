package gregochr.cinefilm.credits.learn;

import java.time.Instant;

/**
 * What {@code GET /api/admin/credits/dataset} answers.
 *
 * @param enabled whether the job is switched on ({@code cinefilm.credits.dataset.enabled})
 * @param state where the job is
 * @param waitingFor why it waits, while the state is {@code WAITING}; otherwise null
 * @param targets how many files have a usable Plex marker
 * @param alreadyComplete how many of them already held a current {@code complete.json} when this run started
 * @param complete how many files this run finished
 * @param failed how many files this run could not read
 * @param skipped how many files this run left because they changed or went
 * @param remaining how many files this run has still to do
 * @param currentMediaFileId the file being read, or null
 * @param refresherDue how many files the credits detection still has due and not held back (the job yields while it
 *     is above 0)
 * @param freeBytes the usable disk, or null when it cannot be read
 * @param minFreeBytes the floor below which the job stops
 * @param startedAt when this run started
 * @param finishedAt when this run ended, or null
 * @param lastError why the run failed, or null
 */
public record DatasetStatus(boolean enabled, State state, Waiting waitingFor, int targets, int alreadyComplete,
                            int complete, int failed, int skipped, int remaining, Long currentMediaFileId,
                            int refresherDue, Long freeBytes, long minFreeBytes, Instant startedAt,
                            Instant finishedAt, String lastError) {

    /** Where the job is. */
    public enum State {
        /** Never started in this process, or switched off. */
        IDLE,
        /** A chain of file tasks is going. */
        RUNNING,
        /** Left for later: playback or the credits detection needs the machine. The hourly nudge resumes it. */
        WAITING,
        /** Stopped by the owner. */
        STOPPED,
        /** Every file is done. */
        DONE,
        /** The disk is below the floor. */
        NO_SPACE,
        /** The job could not start or could not go on; see {@code lastError}. */
        FAILED
    }

    /** Why the job waits. */
    public enum Waiting {
        /** A film is playing or being transcoded. */
        PLAYBACK,
        /** The credits detection has films to analyse first. */
        REFRESHER
    }
}
