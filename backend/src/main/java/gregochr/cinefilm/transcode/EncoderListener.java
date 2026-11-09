package gregochr.cinefilm.transcode;

/** Receives what a running encoder reports. Callbacks run on the encoder's own threads: keep them short. */
public interface EncoderListener {

    /** A progress block arrived. */
    void onProgress(EncoderProgress progress);

    /**
     * The encoder exited (finished, failed, or was stopped); called once, after all progress has been delivered.
     *
     * @param exitCode the process exit status
     * @param logTail the last lines ffmpeg wrote to standard error (bounded), for logging
     */
    void onExit(int exitCode, String logTail);
}
