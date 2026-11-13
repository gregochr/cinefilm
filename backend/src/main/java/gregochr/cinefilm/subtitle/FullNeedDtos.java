package gregochr.cinefilm.subtitle;

import java.util.List;

/** The shapes of the Admin "which films need full English subtitles" API. */
public final class FullNeedDtos {

    private FullNeedDtos() {
    }

    /**
     * One present edition of a film and what its audio is labelled.
     *
     * @param name the edition's name
     * @param audio the language label of part 1's default audio track (else its first), as stored; null when it has no
     *     label or no audio
     */
    public record EditionAudioDto(String name, String audio) {
    }

    /**
     * The full English subtitle a film plays.
     *
     * @param source {@code SIDECAR} (the owner's file beside the film) or {@code DOWNLOAD} (from OpenSubtitles)
     */
    public record FullSubtitleStateDto(String source) {
    }

    /**
     * One film's row.
     *
     * @param filmId the film
     * @param title its title
     * @param year its year, or null
     * @param need {@code NEEDED}, {@code NOT_NEEDED}, {@code UNSURE} or {@code UNKNOWN}
     * @param reason why (see {@link FullNeed.Reason})
     * @param ownerChoice {@code NEEDED}, {@code NOT_NEEDED} or null for automatic
     * @param editions the film's present editions and their audio labels
     * @param fullSubtitle what the film plays as full English subtitles on some edition (the owner's file when any
     *     edition plays one, else a download), or null when no edition plays one
     */
    public record FullNeedFilmDto(Long filmId, String title, Integer year, String need, String reason,
                                  String ownerChoice, List<EditionAudioDto> editions,
                                  FullSubtitleStateDto fullSubtitle) {
    }

    /**
     * How many films are in each state.
     *
     * @param needed films whose need is {@code NEEDED}
     * @param unsure films that are {@code UNSURE}
     * @param unknown films that are {@code UNKNOWN}
     * @param notNeeded films that are {@code NOT_NEEDED}
     */
    public record FullNeedCountsDto(int needed, int unsure, int unknown, int notNeeded) {
    }

    /**
     * The films in scope (a film not made in English, or one the owner chose for), by sort title, with the counts.
     *
     * @param films the rows
     * @param counts how many films are in each state
     */
    public record FullNeedListDto(List<FullNeedFilmDto> films, FullNeedCountsDto counts) {
    }
}
