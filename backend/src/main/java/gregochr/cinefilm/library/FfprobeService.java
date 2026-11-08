package gregochr.cinefilm.library;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Runs {@code ffprobe} on a file: an argument array (no shell), a 60 second timeout, and at most two probes at once
 * (the library is an SMB share on a 2012 Mac mini's network).
 */
@Service
public class FfprobeService {

    /** How long one probe may run before it is killed. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** How many probes may run at the same time. */
    public static final int MAX_CONCURRENT = 2;

    private final CommandRunner runner;
    private final String binary;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    /** Creates the service over a command runner, the ffprobe binary ({@code cinefilm.tools.ffprobe}) and the clock. */
    public FfprobeService(CommandRunner runner, @Value("${cinefilm.tools.ffprobe:ffprobe}") String binary,
                          Clock clock) {
        this.runner = runner;
        this.binary = binary;
        this.clock = clock;
    }

    /**
     * The exact command run for a file. The file is the last element, never part of a larger string, and is always an
     * absolute path from {@link LibraryRoot}, so it can never be read as an option.
     *
     * @param binary the ffprobe executable
     * @param file the media file
     * @return the argument list
     */
    static List<String> command(String binary, Path file) {
        return List.of(binary, "-v", "error", "-print_format", "json", "-show_format", "-show_streams",
            file.toAbsolutePath().toString());
    }

    /**
     * Probes a file.
     *
     * @param file the media file (an absolute path under the library root)
     * @return what ffprobe found
     * @throws ProbeException when ffprobe is missing, fails, times out or returns unusable output
     */
    public ProbeResult probe(Path file) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProbeException("Interrupted before probing", e);
        }
        try {
            CommandRunner.Result result = runner.run(command(binary, file), TIMEOUT);
            if (result.timedOut()) {
                throw new ProbeException("ffprobe timed out after " + TIMEOUT.toSeconds() + " seconds", null);
            }
            if (!result.succeeded()) {
                throw new ProbeException("ffprobe exited with status " + result.exitCode(), null);
            }
            return FfprobeParser.parse(result.stdout(), LocalDate.now(clock).getYear() + 1);
        } catch (IOException e) {
            throw new ProbeException("ffprobe could not be run: " + e.getMessage(), e);
        } finally {
            slots.release();
        }
    }
}
