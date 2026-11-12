package gregochr.cinefilm.subtitle;

import java.util.List;

/**
 * The forced subtitle playback serves for one edition, as cues on the edition's timeline (every owner offset applied,
 * the files of a split edition's parts moved to where each part starts).
 *
 * @param version names exactly what is served: the files' last change, the edition's parts and where each file
 *     starts, so an attach, move, nudge, changed file or changed part timeline gives another string
 * @param ownerVerified true when the owner has said every file served looks right
 * @param cues the cues in time order
 */
public record ServedSubtitle(String version, boolean ownerVerified, List<SubtitleCue> cues) {
}
