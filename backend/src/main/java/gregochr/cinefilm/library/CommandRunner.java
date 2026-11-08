package gregochr.cinefilm.library;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Runs an external program. Implementations take the command as an argument list, never a shell string, so a file
 * name can never be interpreted as shell syntax (SECURITY.md rule 4).
 */
public interface CommandRunner {

    /**
     * The outcome of a run.
     *
     * @param exitCode the process exit code, or -1 when it was killed after the timeout
     * @param stdout everything the program wrote to standard output (capped)
     * @param timedOut true when the timeout expired and the process was killed
     */
    record Result(int exitCode, String stdout, boolean timedOut) {

        /** True when the program finished in time and exited with 0. */
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * Runs the command.
     *
     * @param command the program and its arguments, one element each
     * @param timeout how long to wait before killing the process
     * @return the result
     * @throws IOException when the program cannot be started (for example it is not installed)
     */
    Result run(List<String> command, Duration timeout) throws IOException;
}
