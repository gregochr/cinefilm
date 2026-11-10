package gregochr.cinefilm.transcode;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the HLS playlists the server serves itself, from the cut list and the ladder, before anything is encoded.
 * ffmpeg's own playlists are ignored. Pure functions; lines end with {@code \n}.
 */
public final class PlaylistWriter {

    private PlaylistWriter() {
    }

    /** The segment file name of segment {@code n}, as the encoder writes it and the player requests it. */
    public static String segmentName(int n) {
        return "seg-" + n + ".ts";
    }

    /**
     * A VOD media playlist covering the whole film. A film split across several files has one
     * {@code #EXT-X-DISCONTINUITY} before the first segment of every part after the first, at the same segment in
     * every rung (every rung lists the same cut list).
     *
     * @param cuts the film's segment timeline
     * @return the playlist text
     */
    public static String media(CutList cuts) {
        long longest = 0;
        for (int n = 0; n < cuts.segmentCount(); n++) {
            longest = Math.max(longest, cuts.durationMs(n));
        }
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        out.append("#EXT-X-TARGETDURATION:").append((longest + 999) / 1000).append('\n');
        out.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        List<Integer> partStarts = cuts.partStartSegments();
        for (int n = 0; n < cuts.segmentCount(); n++) {
            if (n > 0 && partStarts.contains(n)) {
                out.append("#EXT-X-DISCONTINUITY\n");
            }
            out.append("#EXTINF:").append(Seconds.format(cuts.durationMs(n))).append(",\n");
            out.append(segmentName(n)).append('\n');
        }
        return out.append("#EXT-X-ENDLIST\n").toString();
    }

    /**
     * The master playlist of one session.
     *
     * @param rungs the film's rungs, best first
     * @param start the rung to list first (Safari starts on the first variant); one of {@code rungs}
     * @param pinned the quality id the viewer chose, or null for adaptive playback: only that rung is listed
     * @param sourceWidth the source picture width, used to give every rung its aspect ratio
     * @param sourceHeight the source picture height
     * @return the playlist text; each rung's media playlist is {@code <id>/index.m3u8}
     * @throws IllegalArgumentException when {@code pinned} names no rung
     */
    public static String master(List<Rung> rungs, Rung start, String pinned, int sourceWidth, int sourceHeight) {
        List<Rung> listed = new ArrayList<>();
        if (pinned == null) {
            listed.add(start);
            rungs.stream().filter(rung -> !rung.equals(start)).forEach(listed::add);
        } else {
            listed.add(rungs.stream().filter(rung -> rung.id().equals(pinned)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No rung " + pinned)));
        }
        StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-INDEPENDENT-SEGMENTS\n");
        for (Rung rung : listed) {
            out.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(rung.peakBandwidth())
                .append(",AVERAGE-BANDWIDTH=").append(rung.averageBandwidth())
                .append(",RESOLUTION=").append(width(rung.height(), sourceWidth, sourceHeight)).append('x')
                .append(rung.height())
                .append(",CODECS=\"").append(codecs(rung)).append("\"\n")
                .append(rung.id()).append("/index.m3u8\n");
        }
        return out.toString();
    }

    /** The even width that keeps the source's aspect ratio at {@code height} lines. */
    static int width(int height, int sourceWidth, int sourceHeight) {
        long scaled = Math.round((double) height * sourceWidth / sourceHeight);
        return (int) (scaled / 2 * 2);
    }

    /**
     * Best-effort {@code CODECS}: H.264 High at a level chosen from the height, and AAC-LC. The copy rung's real
     * profile and level are the source's, which is not stored, so this is a guess for it.
     */
    static String codecs(Rung rung) {
        String level = rung.height() > 720 ? "28" : rung.height() > 480 ? "1f" : "1e";
        return "avc1.6400" + level + ",mp4a.40.2";
    }
}
