package gregochr.cinefilm.transcode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link EncoderProcess} over {@link ProcessBuilder}, as thin as it can be: an argument array, no stdin, standard
 * output parsed for progress and standard error kept as a bounded tail, each on its own virtual thread, and a third
 * virtual thread that reports the exit once both streams are drained.
 */
final class ProcessEncoderProcess implements EncoderProcess {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessEncoderProcess.class);

    private final Process process;
    private final Duration grace;

    private ProcessEncoderProcess(Process process, Duration grace) {
        this.process = process;
        this.grace = grace;
    }

    /**
     * Starts the command.
     *
     * @param command program and arguments
     * @param listener told about progress and the exit
     * @param grace how long {@link #stop()} waits after asking nicely before killing
     * @return the running process
     * @throws IOException when it cannot be started
     */
    static ProcessEncoderProcess start(List<String> command, EncoderListener listener, Duration grace)
        throws IOException {
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        builder.redirectInput(new File("/dev/null"));
        Process process = builder.start();
        LogTail tail = new LogTail();
        Thread progress = Thread.ofVirtual().name("encoder-progress").start(() -> readProgress(
            process.getInputStream(), listener));
        Thread errors = Thread.ofVirtual().name("encoder-stderr").start(() -> readErrors(
            process.getErrorStream(), tail));
        Thread.ofVirtual().name("encoder-exit").start(() -> awaitExit(process, progress, errors, tail, listener));
        return new ProcessEncoderProcess(process, grace);
    }

    private static void readProgress(InputStream stdout, EncoderListener listener) {
        ProgressParser parser = new ProgressParser();
        forEachLine(stdout, line -> parser.accept(line).ifPresent(listener::onProgress));
    }

    private static void readErrors(InputStream stderr, LogTail tail) {
        forEachLine(stderr, tail::add);
    }

    /** Reads lines until the stream ends or fails (a killed process closes its pipes). */
    static void forEachLine(InputStream stream, Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while (line != null) {
                consumer.accept(line);
                line = reader.readLine();
            }
        } catch (IOException e) {
            LOG.debug("Stream closed: {}", e.getMessage());
        }
    }

    static void awaitExit(Process process, Thread progress, Thread errors, LogTail tail,
                                  EncoderListener listener) {
        int code;
        try {
            code = process.waitFor();
            progress.join();
            errors.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return;
        }
        listener.onExit(code, tail.text());
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public OptionalInt exitCode() {
        return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    @Override
    public void stop() {
        process.destroy();
        try {
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
