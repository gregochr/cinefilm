package gregochr.cinefilm.transcode;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The real {@link EncoderFactory}: runs commands under {@code nice} (so playback of other things and the web server
 * stay responsive) and keeps every live encoder so they are all stopped when the application shuts down.
 */
@Component
public class ProcessEncoderFactory implements EncoderFactory {

    /** How long a stopped encoder gets to exit after SIGTERM before it is killed. */
    static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);

    private final int niceness;
    private final Duration grace;
    private final Set<EncoderProcess> live = ConcurrentHashMap.newKeySet();

    /** Creates the factory; {@code cinefilm.transcode.nice} is the niceness (0 or less runs without {@code nice}). */
    @Autowired
    public ProcessEncoderFactory(@Value("${cinefilm.transcode.nice:10}") int niceness) {
        this(niceness, DEFAULT_GRACE);
    }

    ProcessEncoderFactory(int niceness, Duration grace) {
        this.niceness = niceness;
        this.grace = grace;
    }

    /** The command as it is run: wrapped in {@code nice -n <niceness>} when the niceness is positive. */
    List<String> wrap(List<String> command) {
        if (niceness <= 0) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(List.of("nice", "-n", Integer.toString(niceness)));
        wrapped.addAll(command);
        return wrapped;
    }

    @Override
    public EncoderProcess start(List<String> command, EncoderListener listener) throws IOException {
        live.removeIf(process -> !process.isAlive());
        EncoderProcess process = ProcessEncoderProcess.start(wrap(command), listener, grace);
        live.add(process);
        return process;
    }

    /** Stops every encoder still running; called by Spring on shutdown. */
    @PreDestroy
    public void shutdown() {
        for (EncoderProcess process : live) {
            process.stop();
        }
        live.clear();
    }
}
