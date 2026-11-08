package gregochr.cinefilm.library;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * {@link CommandRunner} over {@link ProcessBuilder}: an argument array (never a shell), no standard input, standard
 * error discarded, standard output captured in a temporary file (so a chatty tool can never fill a pipe and hang),
 * capped, and a hard timeout.
 */
@Component
public class ProcessCommandRunner implements CommandRunner {

    /** Standard output beyond this many bytes is dropped (ffprobe JSON for a film is a few KB). */
    static final int MAX_STDOUT_BYTES = 4 * 1024 * 1024;

    @Override
    public Result run(List<String> command, Duration timeout) throws IOException {
        Path capture = Files.createTempFile("cinefilm-tool", ".out");
        try {
            ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectInput(new File("/dev/null"));
            builder.redirectOutput(capture.toFile());
            Process process = builder.start();
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                    return new Result(-1, "", true);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for " + command.get(0), e);
            }
            try (InputStream in = Files.newInputStream(capture)) {
                return new Result(process.exitValue(), new String(in.readNBytes(MAX_STDOUT_BYTES),
                    StandardCharsets.UTF_8), false);
            }
        } finally {
            Files.deleteIfExists(capture);
        }
    }
}
