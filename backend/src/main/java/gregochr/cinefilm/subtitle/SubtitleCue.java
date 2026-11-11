package gregochr.cinefilm.subtitle;

/**
 * One subtitle cue: when it appears and disappears and what it says.
 *
 * @param startMs when the cue appears, in milliseconds from the start of its file
 * @param endMs when the cue disappears
 * @param text the cue text with markup removed; lines are separated by {@code \n} and none is blank
 */
public record SubtitleCue(long startMs, long endMs, String text) {
}
