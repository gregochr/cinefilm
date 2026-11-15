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
     * @param originalLanguage TMDB's original language code, or null
     * @param unresolved true when the need is {@code NEEDED}, no full subtitle plays and nothing automatic is still to
     *     come: the owner has to act (see {@code AdminTodoRules})
     * @param beingChecked true when the need is {@code NEEDED}, no full subtitle plays and an automatic search or
     *     download is still to come
     * @param subtitled true when the film shows in browse, its need is {@code NEEDED} and a full subtitle plays (the
     *     rows {@code AdminTodoRules} calls settled)
     * @param editionId the edition the Admin picker searches OpenSubtitles for: the present edition that
     *     needs them: the first whose audio is labelled with a language other than English, else the first with no
     *     label, else the first (lowest id; part 1 present), or null when the film has no present edition
     * @param editionName that edition's name when the film has more than one present edition (the dialog says which
     *     cut it searches), else null
     */
    public record FullNeedFilmDto(Long filmId, String title, Integer year, String need, String reason,
                                  String ownerChoice, List<EditionAudioDto> editions,
                                  FullSubtitleStateDto fullSubtitle, String originalLanguage, boolean unresolved,
                                  boolean beingChecked, boolean subtitled, Long editionId,
                                  String editionName) {
    }

    /**
     * How many films are in each state.
     *
     * @param needed films whose need is {@code NEEDED}
     * @param unsure films that are {@code UNSURE}
     * @param unknown films that are {@code UNKNOWN}
     * @param notNeeded films that are {@code NOT_NEEDED}
     * @param unresolved films whose row is {@code unresolved}
     * @param beingChecked films whose row is {@code beingChecked}
     * @param subtitled films whose row is {@code subtitled}
     * @param inEnglish browsable films whose full need is {@code NOT_NEEDED}, in or out of this list's scope: English
     *     originals, English dubs and the owner's "not needed". A film with no TMDB language ({@code UNKNOWN}) is not
     *     counted
     */
    public record FullNeedCountsDto(int needed, int unsure, int unknown, int notNeeded, int unresolved,
                                    int beingChecked, int subtitled, int inEnglish) {
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
