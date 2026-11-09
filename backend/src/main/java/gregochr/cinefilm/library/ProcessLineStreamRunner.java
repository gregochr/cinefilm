package gregochr.cinefilm.library;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * {@link LineStreamRunner} over {@link ProcessBuilder}: an argument array (never a shell), no standard input,
 * standard error discarded, standard output read line by line as it is produced, and a watchdog that kills the
 * process when the timeout expires.
 */
@Component
public class ProcessLineStreamRunner implements LineStreamRunner {

    @Override
    public Outcome run(List<String> command, Duration timeout, Consumer<String> lines) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.redirectInput(new File("/dev/null"));
        Process process = builder.start();
        AtomicBoolean timedOut = new AtomicBoolean();
        CompletableFuture<Process> watchdog = process.onExit().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        watchdog.whenComplete((exited, failure) -> {
            if (failure instanceof TimeoutException) {
                timedOut.set(true);
                process.destroyForcibly();
            }
        });
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.accept(line);
                }
            }
            process.waitFor();
            return timedOut.get() ? new Outcome(-1, true) : new Outcome(process.exitValue(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + command.get(0), e);
        } finally {
            process.destroyForcibly();
            watchdog.cancel(true);
        }
    }
}
