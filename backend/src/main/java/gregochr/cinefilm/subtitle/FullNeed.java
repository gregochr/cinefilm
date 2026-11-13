package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.film.SpokenLanguages;
import gregochr.cinefilm.subtitle.ForcedNeed.Languages;
import java.util.List;
import java.util.Locale;

/**
 * Whether a film needs full English subtitles. The first rule that matches wins: the owner's choice, TMDB's languages
 * (no languages, or an English or language-less original), then the audio of the owner's copy. Pure: nothing here reads
 * or writes anything.
 */
public final class FullNeed {

    private static final String ENGLISH = "en";

    /** TMDB's code for "no language" (a silent film, say). */
    private static final String NO_LANGUAGE = "xx";

    private FullNeed() {
    }

    /** The verdict. */
    public enum Need {
        /** The film needs full subtitles. */
        NEEDED,
        /** The film does not need them. */
        NOT_NEEDED,
        /** The audio's language is not labelled: the owner decides. */
        UNSURE,
        /** TMDB has not been asked, or has no film for it. */
        UNKNOWN
    }

    /** Why. */
    public enum Reason {
        /** The owner chose. */
        OWNER,
        /** TMDB has not been asked, or has no film. */
        NO_LANGUAGES,
        /** The film was made in English. */
        ENGLISH_FILM,
        /** An edition's audio is labelled with a language other than English. */
        FOREIGN_AUDIO,
        /** Every edition's audio is labelled English (a dub, or an English co-production). */
        ENGLISH_AUDIO,
        /** No edition's audio is labelled foreign, and not every edition's is labelled English. */
        UNLABELLED_AUDIO
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
     * One audio track as stored.
     *
     * @param language the stored language label, or null
     * @param isDefault whether the file marks it as the default track
     */
    public record Track(String language, boolean isDefault) {
    }

    /**
     * Decides.
     *
     * @param owner the owner's choice, or null for automatic
     * @param languages what TMDB said, or null when it has not been asked or has no film
     * @param editionsAudio one entry per present edition: the language label of part 1's audio (see
     *     {@link #audioLanguage}), null when it has no label or no audio
     * @return the verdict and its reason
     */
    public static Decision decide(FullNeedChoice owner, Languages languages, List<String> editionsAudio) {
        if (owner != null) {
            return new Decision(owner == FullNeedChoice.NEEDED ? Need.NEEDED : Need.NOT_NEEDED, Reason.OWNER);
        }
        if (languages == null) {
            return new Decision(Need.UNKNOWN, Reason.NO_LANGUAGES);
        }
        String original = SpokenLanguages.code(languages.originalLanguage());
        if (original == null || ENGLISH.equals(original) || NO_LANGUAGE.equals(original)) {
            return new Decision(Need.NOT_NEEDED, Reason.ENGLISH_FILM);
        }
        boolean anyForeign = editionsAudio.stream().anyMatch(label -> isLabelled(label) && !isEnglish(label));
        if (anyForeign) {
            return new Decision(Need.NEEDED, Reason.FOREIGN_AUDIO);
        }
        if (!editionsAudio.isEmpty() && editionsAudio.stream().allMatch(FullNeed::isEnglish)) {
            return new Decision(Need.NOT_NEEDED, Reason.ENGLISH_AUDIO);
        }
        return new Decision(Need.UNSURE, Reason.UNLABELLED_AUDIO);
    }

    /**
     * The language of a file's audio: the default track's, else the first track's.
     *
     * @param tracks the file's audio tracks in stream order
     * @return the label as stored, or null when there are no tracks or the chosen one has no label
     */
    public static String audioLanguage(List<Track> tracks) {
        return tracks.stream().filter(Track::isDefault).findFirst()
            .or(() -> tracks.stream().findFirst()).map(Track::language).orElse(null);
    }

    /**
     * True for {@code english}, {@code eng} and {@code en}, in any case, ignoring surrounding spaces. Null and blank
     * are not English: they are unlabelled (unlike {@code SubtitleStream.isEnglishOrUnlabelled}).
     *
     * @param label a stored language label, or null
     * @return whether it names English
     */
    public static boolean isEnglish(String label) {
        if (label == null) {
            return false;
        }
        String lower = label.strip().toLowerCase(Locale.ROOT);
        return "english".equals(lower) || "eng".equals(lower) || ENGLISH.equals(lower);
    }

    private static boolean isLabelled(String label) {
        return label != null && !label.isBlank();
    }
}
