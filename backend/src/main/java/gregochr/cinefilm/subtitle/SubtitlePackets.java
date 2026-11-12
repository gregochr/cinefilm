package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the cue start times of some subtitle streams from ffprobe's packet lines
 * ({@code -show_entries packet=stream_index,pts_time,duration_time,size -of compact=p=0}, one
 * {@code stream_index=3|pts_time=12.345000|duration_time=N/A|size=5234} line per packet). Pure.
 *
 * <p>A text packet or a DVD subtitle packet is one cue: its presentation time is the start. A Blu-ray (PGS) subtitle is
 * a display packet followed by a small "clear" packet that removes it: a clear-only display set is a few segment
 * headers (about 30 bytes in a Matroska file), while the smallest display set that draws anything has a palette and a
 * picture object as well (more than 60 bytes). Packets under {@value #PGS_MIN_BYTES} bytes are therefore dropped, so a
 * clear packet is never counted as a cue. The starts are returned sorted, and starts within {@value #MERGE_MS} ms of
 * the previous one are one cue (two events of one line, or a packet repeated).
 */
final class SubtitlePackets {

    /** A PGS packet smaller than this is a clear packet, not a cue. */
    static final int PGS_MIN_BYTES = 50;
    /** Starts this close together are one cue. */
    static final long MERGE_MS = 50;

    /**
     * The smallest packet that is a cue, by codec. {@code mov_text} (MP4) writes a 2-byte empty sample (a zero length
     * prefix) to end each cue, while a sample with any text is at least 3 bytes.
     */
    private static final Map<String, Integer> MIN_BYTES = Map.of("hdmv_pgs_subtitle", PGS_MIN_BYTES, "mov_text", 3,
        "tx3g", 3);

    private final Map<Integer, String> codecs;
    private final Map<Integer, List<Long>> starts = new HashMap<>();

    /** Reads the packets of the given streams (stream index to codec name); other streams' packets are ignored. */
    SubtitlePackets(Map<Integer, String> codecs) {
        this.codecs = codecs;
        codecs.keySet().forEach(index -> starts.put(index, new ArrayList<>()));
    }

    /** Takes one ffprobe line; anything that is not a usable packet of a wanted stream is ignored. */
    void accept(String line) {
        Integer index = null;
        Double seconds = null;
        Long size = null;
        for (String field : line.strip().split("\\|")) {
            int eq = field.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String value = field.substring(eq + 1);
            switch (field.substring(0, eq)) {
                case "stream_index" -> index = number(value) == null ? null : number(value).intValue();
                case "pts_time" -> seconds = number(value);
                case "size" -> size = number(value) == null ? null : number(value).longValue();
                default -> { }
            }
        }
        if (index == null || seconds == null || !Double.isFinite(seconds) || seconds < 0
            || !codecs.containsKey(index)) {
            return;
        }
        Integer minimum = MIN_BYTES.get(codecs.get(index));
        if (minimum != null && (size == null || size < minimum)) {
            return;
        }
        starts.get(index).add(Math.round(seconds * 1000));
    }

    /** The number, or null for "N/A" and anything else that is not one. */
    private static Double number(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The cue starts of each wanted stream in milliseconds: sorted, starts within {@value #MERGE_MS} ms merged. */
    Map<Integer, List<Long>> starts() {
        Map<Integer, List<Long>> result = new HashMap<>();
        starts.forEach((index, raw) -> result.put(index, merged(raw)));
        return result;
    }

    private static List<Long> merged(List<Long> raw) {
        List<Long> sorted = raw.stream().sorted().toList();
        List<Long> merged = new ArrayList<>();
        for (long start : sorted) {
            if (merged.isEmpty() || start - merged.get(merged.size() - 1) >= MERGE_MS) {
                merged.add(start);
            }
        }
        return List.copyOf(merged);
    }
}
