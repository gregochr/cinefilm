package gregochr.cinefilm.subtitle;

import java.util.List;

/** The Admin API shapes for forced subtitle files. Actions take row ids, never paths. */
public final class SubtitleDtos {

    private SubtitleDtos() {
    }

    /**
     * One forced subtitle file in Admin.
     *
     * @param id the row id every action takes
     * @param path the file's path relative to the library folder (shown, never sent back)
     * @param presence {@code PRESENT} or {@code MISSING} (the last scan did not find the file)
     * @param state {@code matched}, {@code unmatched} or {@code conflict} (two files compete for the same place)
     * @param filmId the film it belongs to, or null when unmatched
     * @param filmTitle that film's title, or null
     * @param editionId the edition it belongs to, or null
     * @param editionName that edition's name, or null
     * @param parts how many files the edition is made of, or 0 when unmatched
     * @param part the part it is timed for (1-based), or null when it is timed for the whole edition or unmatched
     * @param whole true when it is timed for the whole edition
     * @param ownerAttached true when the owner attached it by hand
     * @param cueCount how many cues it has
     * @param parseReport how the file was read, in plain words
     * @param offsetMs the owner's timing nudge in milliseconds
     * @param verified true while the owner's check counts (see {@code verified_parts} in the migration)
     * @param endsAfterRuntime true when the last cue ends after the edition's runtime: wrong film or cut?
     * @param version when the row last changed (epoch milliseconds, a scan that replaced the cues included): a
     *     client that holds the cues asks for them again when it changes
     */
    public record SidecarDto(Long id, String path, String presence, String state, Long filmId, String filmTitle,
                             Long editionId, String editionName, int parts, Integer part, boolean whole,
                             boolean ownerAttached, int cueCount, String parseReport, int offsetMs,
                             boolean verified, boolean endsAfterRuntime, long version) {
    }

    /**
     * One cue of a file on the edition's timeline.
     *
     * @param startMs when it appears, in edition time, the file's offset applied
     * @param endMs when it disappears
     * @param text what it says (untrusted text: render it as text)
     */
    public record CueDto(long startMs, long endMs, String text) {
    }

    /**
     * A file's cues.
     *
     * @param offsetMs the offset already applied to the times
     * @param cues the cues in time order
     */
    public record CuesDto(int offsetMs, List<CueDto> cues) {
    }

    /**
     * The body of an attach: where a file belongs.
     *
     * @param editionId the edition
     * @param part the part it is timed for (1-based), or null for the whole edition
     */
    public record AttachRequest(Long editionId, Integer part) {
    }

    /**
     * The body of a nudge.
     *
     * @param offsetMs the new offset in milliseconds, within ten minutes either way (0 resets it)
     */
    public record OffsetRequest(Integer offsetMs) {
    }
}
