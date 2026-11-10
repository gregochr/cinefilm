package gregochr.cinefilm.transcode;

import java.nio.file.Path;
import java.util.List;

/**
 * What a new session is made of; everything is already validated by {@link SessionService}.
 *
 * @param editionId the edition being played
 * @param filmTitle the film's title (for Admin)
 * @param parts the files the film is made of, in play order (one for a film in one piece), each with the audio
 *     stream chosen for it
 * @param cuts the film's segment timeline: for a split film the parts' cut lists joined (see {@link CutList#join}),
 *     so segment numbers run on across parts; for a film in one piece its own cut list
 * @param width the source picture width
 * @param height the source picture height
 * @param rungs the rungs the encoder makes (only the pinned one when a quality is pinned), best first
 * @param startRung the rung listed first in the master playlist
 * @param pinned the pinned quality id, or null for adaptive playback
 * @param startSegment the (film-wide) segment the first encoder run starts at
 * @param hardwareDecode whether the source (8-bit 4:2:0 H.264, in every part) may be decoded on the GPU; otherwise
 *     the encoder decodes in software and uploads the frames
 */
public record SessionSpec(long editionId, String filmTitle, List<PartSource> parts, CutList cuts, int width,
                          int height, List<Rung> rungs, Rung startRung, String pinned, int startSegment,
                          boolean hardwareDecode) {

    /**
     * One file of the film.
     *
     * @param source the media file, an absolute path resolved under the library root
     * @param audioIndex the position of the chosen audio stream among the file's audio streams
     * @param fps the file's frame rate, or null when unknown
     */
    public record PartSource(Path source, int audioIndex, Double fps) {
    }

    /** A film in one piece. */
    public SessionSpec(long editionId, String filmTitle, Path source, int audioIndex, CutList cuts, Double fps,
                       int width, int height, List<Rung> rungs, Rung startRung, String pinned, int startSegment,
                       boolean hardwareDecode) {
        this(editionId, filmTitle, List.of(new PartSource(source, audioIndex, fps)), cuts, width, height, rungs,
            startRung, pinned, startSegment, hardwareDecode);
    }
}
