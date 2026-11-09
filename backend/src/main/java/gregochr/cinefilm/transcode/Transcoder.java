package gregochr.cinefilm.transcode;

import java.io.IOException;

/** The live encoder behind the sessions. An interface so it can move to a separate worker later. */
public interface Transcoder {

    /** What this server can encode with (detected once); {@code UNAVAILABLE} means only the original plays. */
    EncoderMode mode();

    /**
     * Creates each rung's directory under the plan's output directory, then starts one ffmpeg making every rung
     * of the plan.
     *
     * @param plan what to make
     * @param listener told about progress and the exit
     * @return the running encoder
     * @throws IOException when ffmpeg cannot be started
     */
    EncoderProcess start(TranscodePlan plan, EncoderListener listener) throws IOException;
}
