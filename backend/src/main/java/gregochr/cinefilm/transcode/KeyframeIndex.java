package gregochr.cinefilm.transcode;

import java.util.List;

/**
 * What reading a file's packets gives: the presentation times of its video keyframe packets (the {@code K} packets
 * of {@code ffprobe -show_entries packet=pts_time,flags}, edit list applied) and its average frame rate.
 *
 * @param keyframesMs keyframe presentation times in milliseconds, sorted, without duplicates, never empty
 * @param fps the average frames per second of the first video track, or null when unknown (the ffprobe fallback)
 */
public record KeyframeIndex(List<Long> keyframesMs, Double fps) {
}
