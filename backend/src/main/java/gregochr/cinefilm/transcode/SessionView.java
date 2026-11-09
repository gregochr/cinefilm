package gregochr.cinefilm.transcode;

import java.util.List;

/**
 * What the playlist endpoints need to know about a live session.
 *
 * @param id the session id
 * @param rungs the rungs the session makes, best first
 * @param startRung the rung listed first in the master playlist
 * @param pinned the pinned quality id, or null
 * @param cuts the film's segment timeline
 * @param width the source picture width
 * @param height the source picture height
 */
public record SessionView(String id, List<Rung> rungs, Rung startRung, String pinned, CutList cuts, int width,
                          int height) {
}
