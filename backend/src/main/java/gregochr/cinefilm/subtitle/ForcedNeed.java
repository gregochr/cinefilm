package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.film.SpokenLanguages;
import java.util.List;

/**
 * Whether a film needs forced subtitles, worked out from what is known about it. The first rule that matches wins:
 * the owner's choice, an attached forced subtitle file, an embedded forced English track, OpenSubtitles' evidence,
 * then TMDB's languages and what OpenSubtitles said when asked. The owner never has to decide: only a film
 * OpenSubtitles has not been asked about yet is left {@code UNSURE}. Pure: nothing here reads or writes anything.
 */
public final class ForcedNeed {

    private static final String ENGLISH = "en";

    /** TMDB's code for "no language" (a silent film, say): it says nothing about what is spoken or the origin. */
    private static final String NO_LANGUAGE = "xx";

    private ForcedNeed() {
    }

    /** The verdict. */
    public enum Need {
        /** The film needs forced subtitles. */
        NEEDED,
        /** The film does not need them. */
        NOT_NEEDED,
        /** Not checked online yet: other or no spoken languages, and OpenSubtitles has not been asked. Transient. */
        UNSURE,
        /** TMDB has not been asked, or has no film for it. */
        UNKNOWN
    }

    /** Why. */
    public enum Reason {
        /** The owner chose. */
        OWNER,
        /** A forced subtitle file is attached to one of the film's files. */
        SIDECAR,
        /** One of the film's files has a forced track in English or of no stated language. */
        EMBEDDED_FORCED,
        /** OpenSubtitles has English foreign-parts-only subtitles for the film. */
        OPENSUBTITLES,
        /** TMDB has not been asked, or has no film. */
        NO_LANGUAGES,
        /** The film was not made in English: its full English subtitles (phase 5) carry the foreign lines. */
        FOREIGN_LANGUAGE_FILM,
        /** OpenSubtitles was asked for the film's current IMDb id and has no forced English files for it. */
        NO_FORCED_ONLINE,
        /** TMDB lists English as the only spoken language. */
        ENGLISH_ONLY,
        /** TMDB lists other spoken languages as well as, or instead of, English, and OpenSubtitles is not asked yet. */
        OTHER_LANGUAGES,
        /** TMDB lists no spoken languages, and OpenSubtitles is not asked yet. */
        NO_SPOKEN_LANGUAGES
    }

    /**
     * The verdict and its reason.
     *
     * @param need the verdict
     * @param reason why
     */
    public record Decision(Need need, Reason reason) {
    }

    /**
     * What TMDB said about a film's languages.
     *
     * @param originalLanguage the original language code, or null when TMDB gave none
     * @param spokenLanguages the spoken language codes (an empty list when TMDB lists none)
     */
    public record Languages(String originalLanguage, List<String> spokenLanguages) {
    }

    /**
     * Decides.
     *
     * @param owner the owner's choice, or null for automatic
     * @param hasSidecar true when a present forced subtitle file is attached to one of the film's files
     * @param hasEmbeddedForced true when one of the film's files has a forced track in English or of no language
     * @param openSubtitlesForcedCount how many English foreign-parts-only files OpenSubtitles has for the film; null
     *     when it has not been asked, or was asked for another IMDb id than the film has now
     * @param languages what TMDB said, or null when it has not been asked or has no film
     * @return the verdict and its reason
     */
    public static Decision decide(ForcedNeedChoice owner, boolean hasSidecar, boolean hasEmbeddedForced,
                                  Integer openSubtitlesForcedCount, Languages languages) {
        if (owner != null) {
            return new Decision(owner == ForcedNeedChoice.NEEDED ? Need.NEEDED : Need.NOT_NEEDED, Reason.OWNER);
        }
        if (hasSidecar) {
            return new Decision(Need.NEEDED, Reason.SIDECAR);
        }
        if (hasEmbeddedForced) {
            return new Decision(Need.NEEDED, Reason.EMBEDDED_FORCED);
        }
        if (openSubtitlesForcedCount != null && openSubtitlesForcedCount > 0) {
            return new Decision(Need.NEEDED, Reason.OPENSUBTITLES);
        }
        if (languages == null) {
            return new Decision(Need.UNKNOWN, Reason.NO_LANGUAGES);
        }
        String original = SpokenLanguages.code(languages.originalLanguage());
        if (original != null && !ENGLISH.equals(original) && !NO_LANGUAGE.equals(original)) {
            return new Decision(Need.NOT_NEEDED, Reason.FOREIGN_LANGUAGE_FILM);
        }
        List<String> spoken = SpokenLanguages.codes(languages.spokenLanguages()).stream()
            .filter(code -> !NO_LANGUAGE.equals(code)).toList();
        if (spoken.equals(List.of(ENGLISH))) {
            return new Decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY);
        }
        if (openSubtitlesForcedCount != null) {
            return new Decision(Need.NOT_NEEDED, Reason.NO_FORCED_ONLINE);
        }
        return new Decision(Need.UNSURE, spoken.isEmpty() ? Reason.NO_SPOKEN_LANGUAGES : Reason.OTHER_LANGUAGES);
    }
}
