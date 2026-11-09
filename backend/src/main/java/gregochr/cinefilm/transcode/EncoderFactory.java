package gregochr.cinefilm.transcode;

import java.io.IOException;
import java.util.List;

/** Starts encoder processes. The session manager takes this interface so tests can inject a fake. */
public interface EncoderFactory {

    /**
     * Starts a command as a long-running encoder.
     *
     * @param command the program and its arguments, one element each (never a shell string)
     * @param listener told about progress and the exit
     * @return the running process
     * @throws IOException when the program cannot be started
     */
    EncoderProcess start(List<String> command, EncoderListener listener) throws IOException;
}
