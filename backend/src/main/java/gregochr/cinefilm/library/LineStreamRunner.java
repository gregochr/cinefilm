package gregochr.cinefilm.library;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs an external program and hands its standard output over one line at a time, as it is written, so the caller
 * keeps only what it needs (unlike {@link CommandRunner}, which buffers up to a cap). The command is an argument
 * list, never a shell string.
 */
public interface LineStreamRunner {

    /**
     * The outcome of a run.
     *
     * @param exitCode the process exit code, or -1 when it was killed after the timeout
     * @param timedOut true when the timeout expired and the process was killed
     */
    record Outcome(int exitCode, boolean timedOut) {

        /** True when the program finished in time and exited with 0. */
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * Runs the command, passing each line of standard output to the handler as it arrives (on the calling thread).
     *
     * @param command the program and its arguments, one element each
     * @param timeout how long to wait before killing the process
     * @param lines receives each output line, without its line terminator
     * @return how the run ended; lines seen before a failure or timeout are not to be trusted as complete
     * @throws IOException when the program cannot be started or its output cannot be read
     */
    Outcome run(List<String> command, Duration timeout, Consumer<String> lines) throws IOException;
}
