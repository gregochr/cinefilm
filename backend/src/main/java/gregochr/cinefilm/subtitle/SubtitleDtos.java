package gregochr.cinefilm.subtitle;

import java.time.Instant;
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
     * @param served true when playback serves this file for its edition (the edition's forced subtitle is made from
     *     it): false for an unmatched, empty, missing or conflicting file, or one that a whole-edition file beats
     * @param check the comparison with OpenSubtitles' file for the edition this file is served for, or null when the
     *     edition does not serve this file, has not been compared yet, or what is served changed since
     */
    public record SidecarDto(Long id, String path, String presence, String state, Long filmId, String filmTitle,
                             Long editionId, String editionName, int parts, Integer part, boolean whole,
                             boolean ownerAttached, int cueCount, String parseReport, int offsetMs,
                             boolean verified, boolean endsAfterRuntime, long version, boolean served, CheckDto check) {
    }

    /**
     * What comparing the subtitle an edition serves with OpenSubtitles' file for the film found
     * ({@code SubtitleComparison}).
     *
     * @param verdict {@code AGREES}, or {@code CHECK}: incomplete, for another cut or not forced-only
     * @param reasons plain sentences: why it is {@code CHECK}, and a timing hint such as "OpenSubtitles' file is 1.2 s
     *     later" (never applied automatically)
     * @param bestOffsetMs the milliseconds to add to this file to line up best with OpenSubtitles' (a hint)
     * @param matchedShare the share (0 to 1) of this file's lines that line up at that offset
     * @param servedCount how many lines the edition serves
     * @param referenceCount how many lines OpenSubtitles' file has
     * @param referenceRelease the release name of OpenSubtitles' file, or null
     * @param checkedAt when the comparison was made
     */
    public record CheckDto(String verdict, List<String> reasons, int bestOffsetMs, double matchedShare,
                           int servedCount, int referenceCount, String referenceRelease, Instant checkedAt) {
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
