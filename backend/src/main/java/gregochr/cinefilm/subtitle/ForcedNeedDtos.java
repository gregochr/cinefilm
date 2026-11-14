package gregochr.cinefilm.subtitle;

import java.time.Instant;
import java.util.List;

/** The shapes of the Admin "which films need forced subtitles" API. */
public final class ForcedNeedDtos {

    private ForcedNeedDtos() {
    }

    /**
     * One film's row.
     *
     * @param filmId the film
     * @param title its title
     * @param year its year, or null
     * @param need {@code NEEDED}, {@code NOT_NEEDED}, {@code UNSURE} or {@code UNKNOWN}
     * @param reason why (see {@link ForcedNeed.Reason})
     * @param ownerChoice {@code NEEDED}, {@code NOT_NEEDED} or null for automatic
     * @param originalLanguage TMDB's original language code, or null
     * @param spokenLanguages TMDB's spoken language codes (empty when none or unknown)
     * @param hasForcedSubtitle true when at least one edition of the film has a forced subtitle that playback
     *     would serve (the film detail's rules: a file with no cues, or a conflict, is not served)
     * @param openSubtitlesForcedCount how many English foreign-parts-only subtitles OpenSubtitles had when last
     *     searched for the film's current IMDb id; null when it has not been searched (or the IMDb id changed since)
     * @param openSubtitlesSearchedAt when that search was made; null with the count
     * @param unresolved true when the need is {@code NEEDED}, no forced subtitle is served and nothing automatic is
     *     still to come: the owner has to act (see {@code AdminTodoRules})
     * @param beingChecked true when the need is {@code NEEDED}, no forced subtitle is served and an automatic search
     *     or download is still to come
     */
    public record FilmNeedDto(Long filmId, String title, Integer year, String need, String reason,
                              String ownerChoice, String originalLanguage, List<String> spokenLanguages,
                              boolean hasForcedSubtitle, Integer openSubtitlesForcedCount,
                              Instant openSubtitlesSearchedAt, boolean unresolved, boolean beingChecked) {
    }

    /**
     * How many films are in each state.
     *
     * @param needed films whose need is {@code NEEDED}
     * @param neededMissing of those, the ones with no forced subtitle playback would serve
     *     ({@code hasForcedSubtitle} false)
     * @param unsure films that are {@code UNSURE}
     * @param unknown films that are {@code UNKNOWN}
     * @param notNeeded films that are {@code NOT_NEEDED}
     * @param unresolved films whose row is {@code unresolved}
     * @param beingChecked films whose row is {@code beingChecked}
     */
    public record CountsDto(int needed, int neededMissing, int unsure, int unknown, int notNeeded, int unresolved,
                            int beingChecked) {
    }

    /**
     * Every film, by sort title, with the counts.
     *
     * @param films the rows
     * @param counts how many films are in each state
     */
    public record NeedListDto(List<FilmNeedDto> films, CountsDto counts) {
    }

    /**
     * The owner's choice for a film.
     *
     * @param choice {@code NEEDED}, {@code NOT_NEEDED}, or null to leave it to the automatic rule
     */
    public record ChoiceRequest(String choice) {
    }
}
