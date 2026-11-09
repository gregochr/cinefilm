package gregochr.cinefilm.transcode;

import java.util.OptionalInt;

/** A running (or finished) encoder: one long-lived ffmpeg. */
public interface EncoderProcess {

    /** True until the process has exited. */
    boolean isAlive();

    /** The exit status, or empty while the process is still running. */
    OptionalInt exitCode();

    /** Asks the process to end and kills it if it does not. */
    void stop();
}
