package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.ForcedNeedChoice;
import gregochr.cinefilm.subtitle.ForcedNeed.Decision;
import gregochr.cinefilm.subtitle.ForcedNeed.Languages;
import gregochr.cinefilm.subtitle.ForcedNeed.Need;
import gregochr.cinefilm.subtitle.ForcedNeed.Reason;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rule table of "does this film need forced subtitles", row by row and pair by pair. */
class ForcedNeedTest {

    private static final Languages ENGLISH_ONLY = new Languages("en", List.of("en"));
    private static final Languages FRENCH_FILM = new Languages("fr", List.of("fr", "en"));

    private static Decision of(ForcedNeedChoice owner, boolean sidecar, boolean embedded, Integer openSubtitles,
                               Languages languages) {
        return ForcedNeed.decide(owner, sidecar, embedded, openSubtitles, languages);
    }

    private static Decision byLanguages(Languages languages) {
        return of(null, false, false, null, languages);
    }

    private static Decision decision(Need need, Reason reason) {
        return new Decision(need, reason);
    }

    @Test
    void ruleOneTheOwnersChoiceIsTheAnswerWhateverElseIsKnown() {
        assertThat(of(ForcedNeedChoice.NEEDED, false, false, null, ENGLISH_ONLY))
            .isEqualTo(decision(Need.NEEDED, Reason.OWNER));
        assertThat(of(ForcedNeedChoice.NOT_NEEDED, true, true, 7, FRENCH_FILM))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.OWNER));
    }

    @Test
    void ruleTwoAnAttachedForcedSubtitleFileMeansNeeded() {
        assertThat(of(null, true, false, null, null)).isEqualTo(decision(Need.NEEDED, Reason.SIDECAR));
        assertThat(of(null, true, false, null, ENGLISH_ONLY)).isEqualTo(decision(Need.NEEDED, Reason.SIDECAR));
    }

    @Test
    void ruleThreeAnEmbeddedForcedTrackMeansNeeded() {
        assertThat(of(null, false, true, null, null)).isEqualTo(decision(Need.NEEDED, Reason.EMBEDDED_FORCED));
        assertThat(of(null, false, true, null, ENGLISH_ONLY))
            .isEqualTo(decision(Need.NEEDED, Reason.EMBEDDED_FORCED));
    }

    @Test
    void ruleFourOpenSubtitlesEvidenceMeansNeededOnlyWhenThereIsSome() {
        assertThat(of(null, false, false, 1, ENGLISH_ONLY)).isEqualTo(decision(Need.NEEDED, Reason.OPENSUBTITLES));
        assertThat(of(null, false, false, 7, null)).isEqualTo(decision(Need.NEEDED, Reason.OPENSUBTITLES));
        assertThat(of(null, false, false, 0, ENGLISH_ONLY)).isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
        assertThat(of(null, false, false, null, ENGLISH_ONLY))
            .isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
    }

    @Test
    void ruleFiveNothingFromTmdbIsUnknown() {
        assertThat(byLanguages(null)).isEqualTo(decision(Need.UNKNOWN, Reason.NO_LANGUAGES));
        assertThat(of(null, false, false, 0, null)).isEqualTo(decision(Need.UNKNOWN, Reason.NO_LANGUAGES));
    }

    @Test
    void ruleSixAFilmNotMadeInEnglishIsUnsureWhatItsSpokenLanguagesAre() {
        assertThat(byLanguages(FRENCH_FILM)).isEqualTo(decision(Need.UNSURE, Reason.FOREIGN_LANGUAGE_FILM));
        assertThat(byLanguages(new Languages("ja", List.of("en")))).isEqualTo(
            decision(Need.UNSURE, Reason.FOREIGN_LANGUAGE_FILM));
        assertThat(byLanguages(new Languages("ja", List.of()))).isEqualTo(
            decision(Need.UNSURE, Reason.FOREIGN_LANGUAGE_FILM));
    }

    @Test
    void ruleSixLeavesAMissingOriginalLanguageToTheSpokenLanguages() {
        assertThat(byLanguages(new Languages(null, List.of("en")))).isEqualTo(
            decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
        assertThat(byLanguages(new Languages(null, List.of("fr")))).isEqualTo(
            decision(Need.UNSURE, Reason.OTHER_LANGUAGES));
        assertThat(byLanguages(new Languages("12", List.of("en")))).isEqualTo(
            decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
    }

    @Test
    void ruleSevenEnglishAsTheOnlySpokenLanguageMeansNotNeeded() {
        assertThat(byLanguages(ENGLISH_ONLY)).isEqualTo(decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
    }

    @Test
    void noLanguageXxIsIgnoredSoEnglishAndXxIsStillEnglishOnly() {
        assertThat(byLanguages(new Languages("en", List.of("en", "xx")))).isEqualTo(
            decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
        assertThat(byLanguages(new Languages("en", List.of("xx", "en")))).isEqualTo(
            decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
    }

    @Test
    void xxAsTheOriginalLanguageIsAbsentSoTheSpokenLanguagesDecide() {
        for (String none : List.of("xx", "XX")) {
            assertThat(byLanguages(new Languages(none, List.of("en")))).isEqualTo(
                decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
            assertThat(byLanguages(new Languages(none, List.of()))).isEqualTo(
                decision(Need.UNSURE, Reason.NO_SPOKEN_LANGUAGES));
            assertThat(byLanguages(new Languages(none, List.of("fr")))).isEqualTo(
                decision(Need.UNSURE, Reason.OTHER_LANGUAGES));
        }
    }

    @Test
    void xxAloneIsNoSpokenLanguages() {
        assertThat(byLanguages(new Languages("en", List.of("xx")))).isEqualTo(
            decision(Need.UNSURE, Reason.NO_SPOKEN_LANGUAGES));
    }

    @Test
    void codesAreReadInAnyCaseAndRepeatsDoNotCount() {
        assertThat(byLanguages(new Languages("EN", List.of("EN", "en")))).isEqualTo(
            decision(Need.NOT_NEEDED, Reason.ENGLISH_ONLY));
        assertThat(byLanguages(new Languages("Fr", List.of("en")))).isEqualTo(
            decision(Need.UNSURE, Reason.FOREIGN_LANGUAGE_FILM));
    }

    @Test
    void ruleEightOtherSpokenLanguagesAreUnsure() {
        assertThat(byLanguages(new Languages("en", List.of("en", "fr", "ru")))).isEqualTo(
            decision(Need.UNSURE, Reason.OTHER_LANGUAGES));
        assertThat(byLanguages(new Languages("en", List.of("fr")))).isEqualTo(
            decision(Need.UNSURE, Reason.OTHER_LANGUAGES));
    }

    @Test
    void ruleEightNoSpokenLanguagesListedIsUnsureWithItsOwnReason() {
        assertThat(byLanguages(new Languages("en", List.of()))).isEqualTo(
            decision(Need.UNSURE, Reason.NO_SPOKEN_LANGUAGES));
    }

    @Test
    void everyEarlierRuleBeatsEveryLaterOne() {
        Languages foreign = FRENCH_FILM;
        // owner over sidecar, embedded, OpenSubtitles and TMDB
        assertThat(of(ForcedNeedChoice.NOT_NEEDED, true, false, null, null).reason()).isEqualTo(Reason.OWNER);
        assertThat(of(ForcedNeedChoice.NOT_NEEDED, false, true, null, null).reason()).isEqualTo(Reason.OWNER);
        assertThat(of(ForcedNeedChoice.NOT_NEEDED, false, false, 3, null).reason()).isEqualTo(Reason.OWNER);
        assertThat(of(ForcedNeedChoice.NEEDED, false, false, null, foreign).reason()).isEqualTo(Reason.OWNER);
        // sidecar over embedded, OpenSubtitles and TMDB
        assertThat(of(null, true, true, null, null).reason()).isEqualTo(Reason.SIDECAR);
        assertThat(of(null, true, false, 3, null).reason()).isEqualTo(Reason.SIDECAR);
        assertThat(of(null, true, false, null, foreign).reason()).isEqualTo(Reason.SIDECAR);
        // embedded over OpenSubtitles and TMDB
        assertThat(of(null, false, true, 3, null).reason()).isEqualTo(Reason.EMBEDDED_FORCED);
        assertThat(of(null, false, true, null, foreign).reason()).isEqualTo(Reason.EMBEDDED_FORCED);
        // OpenSubtitles over TMDB
        assertThat(of(null, false, false, 3, foreign).reason()).isEqualTo(Reason.OPENSUBTITLES);
        // no languages over nothing else; foreign over English-only
        assertThat(of(null, false, false, null, new Languages("fr", List.of("en"))).reason())
            .isEqualTo(Reason.FOREIGN_LANGUAGE_FILM);
    }
}
