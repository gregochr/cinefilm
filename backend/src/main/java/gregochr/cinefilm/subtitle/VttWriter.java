package gregochr.cinefilm.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes cues as WebVTT. Cue text is untrusted (a hand-edited or downloaded file), so {@code &}, {@code <} and
 * {@code >} are escaped: nothing in a cue can become markup or a timing line. Pure.
 */
final class VttWriter {

    /** A cue with no duration is shown for this long, since a browser drops a cue that ends when it starts. */
    static final long MIN_DISPLAY_MS = 1000;

    private VttWriter() {
    }

    /**
     * The WebVTT file of some cues with a timing offset applied. A cue that is over before 0 after a negative offset
     * is dropped, one that starts before 0 starts at 0, and a cue with no duration lasts {@link #MIN_DISPLAY_MS}.
     *
     * @param cues the cues in time order
     * @param offsetMs milliseconds added to every cue time (negative shows the lines earlier)
     * @return the WebVTT text, ending with a newline
     */
    static String write(List<SubtitleCue> cues, long offsetMs) {
        StringBuilder out = new StringBuilder("WEBVTT\n");
        for (SubtitleCue cue : shift(cues, offsetMs)) {
            out.append('\n').append(time(cue.startMs())).append(" --> ").append(time(cue.endMs())).append('\n')
                .append(escape(cue.text())).append('\n');
        }
        return out.toString();
    }

    /**
     * The cues as they are shown after an offset: a cue that is over before 0 is dropped, one that starts before 0
     * starts at 0, and a cue with no duration lasts {@link #MIN_DISPLAY_MS}. What {@link #write} writes.
     *
     * @param cues the cues in time order
     * @param offsetMs milliseconds added to every cue time
     * @return the cues that are shown, in the same order
     */
    static List<SubtitleCue> shift(List<SubtitleCue> cues, long offsetMs) {
        List<SubtitleCue> shown = new ArrayList<>();
        for (SubtitleCue cue : cues) {
            long end = cue.endMs() + offsetMs;
            boolean noDuration = cue.endMs() <= cue.startMs();
            if (end < 0 || end == 0 && !noDuration) {
                continue;
            }
            long start = Math.max(0, cue.startMs() + offsetMs);
            if (end <= start) {
                end = start + MIN_DISPLAY_MS;
            }
            shown.add(new SubtitleCue(start, end, cue.text()));
        }
        return shown;
    }

    /** {@code hh:mm:ss.mmm}. */
    static String time(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60,
            ms % 1000);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
