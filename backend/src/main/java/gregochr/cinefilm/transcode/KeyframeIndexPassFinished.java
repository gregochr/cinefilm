package gregochr.cinefilm.transcode;

/**
 * Published when a pass of the keyframe index ends, however many files it indexed: a film's frame rate
 * ({@code media_keyframes.fps}) may have arrived, so the subtitle retime job works it out again.
 */
public record KeyframeIndexPassFinished() {
}
