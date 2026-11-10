package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Joins the WebVTT subtitle files of a split film's parts into one, moving each later part's cues onto the film's
 * timeline. Pure text work: a cue timing line is {@code start --> end} followed by optional cue settings, each time
 * {@code hh:mm:ss.mmm} or {@code mm:ss.mmm}; the text of a cue is left exactly as it is.
 */
final class VttShifter {

    private static final Pattern TIMING = Pattern.compile(
        "^(\\s*)((?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3})(\\s+-->\\s+)((?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3})(.*)$");
    private static final Pattern BLOCK_BREAK = Pattern.compile("\n{2,}");

    private VttShifter() {
    }

    /**
     * Joins the subtitle files of a film's parts into one file on the film's timeline: the first file as it is (its
     * header included), then only the cues of each later file, shifted by that part's start. A later file's header,
     * notes and cue identifiers are dropped (identifiers must be unique in one file and the parts' would repeat).
     *
     * @param vtts the parts' WebVTT files in play order, at least one
     * @param offsetsMs where each part starts on the film's timeline in milliseconds; the first is 0
     * @return one WebVTT file
     */
    static String join(List<String> vtts, List<Long> offsetsMs) {
        StringBuilder out = new StringBuilder(normalise(vtts.get(0)).stripTrailing());
        for (int i = 1; i < vtts.size(); i++) {
            for (String block : BLOCK_BREAK.split(normalise(vtts.get(i)).strip())) {
                String cue = cue(block, offsetsMs.get(i));
                if (cue != null) {
                    out.append("\n\n").append(cue);
                }
            }
        }
        return out.append('\n').toString();
    }

    /** The shifted cue of a block (without its identifier), or null when the block is not a cue. */
    private static String cue(String block, long offsetMs) {
        String[] lines = block.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher timing = TIMING.matcher(lines[i]);
            if (timing.matches()) {
                List<String> kept = new ArrayList<>();
                kept.add(timing.group(1) + format(parse(timing.group(2)) + offsetMs) + timing.group(3)
                    + format(parse(timing.group(4)) + offsetMs) + timing.group(5));
                kept.addAll(List.of(lines).subList(i + 1, lines.length));
                return String.join("\n", kept);
            }
        }
        return null;
    }

    private static long parse(String time) {
        String[] fields = time.split("[:.]");
        long hours = fields.length == 4 ? Long.parseLong(fields[0]) : 0;
        return ((hours * 60 + Long.parseLong(fields[fields.length - 3])) * 60
            + Long.parseLong(fields[fields.length - 2])) * 1000 + Long.parseLong(fields[fields.length - 1]);
    }

    private static String format(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60,
            ms % 1000);
    }

    private static String normalise(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
