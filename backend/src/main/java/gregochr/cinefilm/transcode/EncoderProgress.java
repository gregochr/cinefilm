package gregochr.cinefilm.transcode;

/**
 * One progress report from ffmpeg's {@code -progress} output.
 *
 * @param outTimeMs how much has been written, in milliseconds of output time (on the film timeline when the
 *     encoder keeps its timestamps, so after a restart at a cut it starts there)
 * @param speed encoding speed as a multiple of real time, 0 when ffmpeg has not measured it yet
 */
public record EncoderProgress(long outTimeMs, double speed) {
}
