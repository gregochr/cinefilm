package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.FullNeedChoice;
import gregochr.cinefilm.subtitle.ForcedNeed.Languages;
import gregochr.cinefilm.subtitle.FullNeed.Decision;
import gregochr.cinefilm.subtitle.FullNeed.Need;
import gregochr.cinefilm.subtitle.FullNeed.Reason;
import gregochr.cinefilm.subtitle.FullNeed.Track;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The rule table of "does this film need full English subtitles", in order. */
class FullNeedTest {

    private static final Languages FRENCH_FILM = new Languages("fr", List.of("fr"));
    private static final Languages ENGLISH_FILM = new Languages("en", List.of("en", "fr"));

    private static Decision decision(Need need, Reason reason) {
        return new Decision(need, reason);
    }

    private static Decision audio(String... labels) {
        return FullNeed.decide(null, FRENCH_FILM, Arrays.asList(labels));
    }

    @Test
    void ruleOneTheOwnersChoiceBeatsEverything() {
        assertThat(FullNeed.decide(FullNeedChoice.NEEDED, null, List.of()))
            .isEqualTo(decision(Need.NEEDED, Reason.OWNER));
        assertThat(FullNeed.decide(FullNeedChoice.NEEDED, ENGLISH_FILM, List.of("English")))
            .isEqualTo(decision(Need.NEEDED, Reason.OWNER));
        assertThat(FullNeed.decide(FullNeedChoice.NOT_NEEDED, FRENCH_FILM, List.of("French")))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.OWNER));
    }

    @Test
    void ruleTwoNoTmdbLanguagesIsUnknownWhateverTheAudio() {
        assertThat(FullNeed.decide(null, null, List.of("French")))
            .isEqualTo(decision(Need.UNKNOWN, Reason.NO_LANGUAGES));
    }

    @Test
    void ruleThreeAnEnglishOrLanguageLessOriginalIsNotNeededWhateverTheAudio() {
        assertThat(FullNeed.decide(null, ENGLISH_FILM, List.of("French")))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_FILM));
        assertThat(FullNeed.decide(null, new Languages("EN", List.of()), List.of()))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_FILM));
        assertThat(FullNeed.decide(null, new Languages("xx", List.of()), List.of("French")))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_FILM));
        assertThat(FullNeed.decide(null, new Languages(null, List.of("fr")), List.of("French")))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_FILM));
    }

    @Test
    void ruleFourForeignAudioInAnyEditionMeansNeeded() {
        assertThat(audio("French")).isEqualTo(decision(Need.NEEDED, Reason.FOREIGN_AUDIO));
        assertThat(audio("English", "French")).isEqualTo(decision(Need.NEEDED, Reason.FOREIGN_AUDIO));
        assertThat(audio(null, "fr")).isEqualTo(decision(Need.NEEDED, Reason.FOREIGN_AUDIO));
    }

    @Test
    void ruleFourEnglishAudioInEveryEditionMeansNotNeeded() {
        assertThat(audio("English")).isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_AUDIO));
        assertThat(audio("English", "eng", " EN ")).isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_AUDIO));
    }

    @Test
    void ruleFourUnlabelledAudioAssumesTheOriginalLanguageSoItIsNeeded() {
        assertThat(audio((String) null)).isEqualTo(decision(Need.NEEDED, Reason.ORIGINAL_AUDIO_ASSUMED));
        assertThat(audio(null, null)).isEqualTo(decision(Need.NEEDED, Reason.ORIGINAL_AUDIO_ASSUMED));
        assertThat(audio("English", null)).isEqualTo(decision(Need.NEEDED, Reason.ORIGINAL_AUDIO_ASSUMED));
        assertThat(audio(" ")).isEqualTo(decision(Need.NEEDED, Reason.ORIGINAL_AUDIO_ASSUMED));
    }

    @Test
    void noPresentEditionIsTreatedAsUnlabelledSoItIsNeeded() {
        assertThat(audio()).isEqualTo(decision(Need.NEEDED, Reason.ORIGINAL_AUDIO_ASSUMED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"English", "ENG", " en ", "english", "Eng"})
    void theseLabelsAreEnglish(String label) {
        assertThat(FullNeed.isEnglish(label)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr", "French", "und", "english-us", "e", " ", "\t"})
    @NullAndEmptySource
    void theseLabelsAreNotEnglish(String label) {
        assertThat(FullNeed.isEnglish(label)).isFalse();
    }

    @Test
    void theAudioLanguageIsTheDefaultTracksElseTheFirstTracksElseNull() {
        assertThat(FullNeed.audioLanguage(List.of(new Track("English", false), new Track("French", true))))
            .isEqualTo("French");
        assertThat(FullNeed.audioLanguage(List.of(new Track("French", false), new Track("English", false))))
            .isEqualTo("French");
        assertThat(FullNeed.audioLanguage(List.of(new Track(null, true), new Track("English", false)))).isNull();
        assertThat(FullNeed.audioLanguage(List.of())).isNull();
    }
}
