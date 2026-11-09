package gregochr.cinefilm.transcode;

import java.nio.file.Path;
import java.util.List;

/**
 * What a new session is made of; everything is already validated by {@link SessionService}.
 *
 * @param editionId the edition being played
 * @param filmTitle the film's title (for Admin)
 * @param source the media file, an absolute path resolved under the library root
 * @param audioIndex the position of the chosen audio stream among the file's audio streams
 * @param cuts the film's segment timeline
 * @param fps the film's frame rate, or null when unknown
 * @param width the source picture width
 * @param height the source picture height
 * @param rungs the rungs the encoder makes (only the pinned one when a quality is pinned), best first
 * @param startRung the rung listed first in the master playlist
 * @param pinned the pinned quality id, or null for adaptive playback
 * @param startSegment the segment the first encoder run starts at
 */
public record SessionSpec(long editionId, String filmTitle, Path source, int audioIndex, CutList cuts, Double fps,
                          int width, int height, List<Rung> rungs, Rung startRung, String pinned,
                          int startSegment) {
}
