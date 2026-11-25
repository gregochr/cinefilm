package gregochr.cinefilm.subtitle;

import java.util.List;

/** Puts stored SRT cues on another timeline: every start and end multiplied by a factor, text and order unchanged. */
public final class CueScaler {

    private CueScaler() {
    }

    /**
     * Scales the cue times of normalised SRT text (the format {@link SrtParser#format} writes).
     *
     * @param cuesText the stored SRT
     * @param k the factor, from {@link FrameRateRetime#factor}
     * @return the text with every start and end times k, rounded to the millisecond; the same text when k is 1
     */
    public static String scale(String cuesText, double k) {
        if (k == 1.0) {
            return cuesText;
        }
        List<SubtitleCue> scaled = SrtParser.cues(cuesText).stream()
            .map(cue -> new SubtitleCue(Math.round(cue.startMs() * k), Math.round(cue.endMs() * k), cue.text()))
            .toList();
        return SrtParser.format(scaled);
    }
}
