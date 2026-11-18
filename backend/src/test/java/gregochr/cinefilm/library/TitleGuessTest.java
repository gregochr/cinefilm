package gregochr.cinefilm.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TitleGuessTest {

    @Test
    void theFileNameWithoutItsExtensionIsTheTitleWhenThereIsNoEmbeddedTitle() {
        assertThat(TitleGuess.of(null, "10 Cloverfield Lane.m4v", null))
            .isEqualTo(new TitleGuess("10 Cloverfield Lane", null));
        assertThat(TitleGuess.of("  ", "101 Dalmations Cartoon.m4v", null))
            .isEqualTo(new TitleGuess("101 Dalmations Cartoon", null));
    }

    @Test
    void theEmbeddedTitleIsPreferredToTheFileName() {
        assertThat(TitleGuess.of(" Heat ", "heat_final_v2.m4v", null)).isEqualTo(new TitleGuess("Heat", null));
    }

    @Test
    void aTrailingYearInRoundOrSquareBracketsIsStrippedAndUsed() {
        assertThat(TitleGuess.of(null, "Heat (1995).m4v", null)).isEqualTo(new TitleGuess("Heat", 1995));
        assertThat(TitleGuess.of(null, "Heat [1995].mp4", null)).isEqualTo(new TitleGuess("Heat", 1995));
        assertThat(TitleGuess.of("Heat (1995)", "x.m4v", null)).isEqualTo(new TitleGuess("Heat", 1995));
    }

    @Test
    void yearBoundsAreTheFirstFilmAndTheEndOfTheCentury() {
        assertThat(TitleGuess.of(null, "Roundhay Garden Scene (1888).mov", null).year()).isEqualTo(1888);
        assertThat(TitleGuess.of(null, "Too Early (1887).mov", null))
            .isEqualTo(new TitleGuess("Too Early (1887)", null));
        assertThat(TitleGuess.of(null, "Far Future (2100).mov", null).year()).isEqualTo(2100);
        assertThat(TitleGuess.of(null, "Far Future (2101).mov", null))
            .isEqualTo(new TitleGuess("Far Future (2101)", null));
    }

    @Test
    void aTitleThatIsOnlyAYearKeepsItAsTheTitle() {
        assertThat(TitleGuess.of(null, "(1999).m4v", null)).isEqualTo(new TitleGuess("(1999)", null));
        assertThat(TitleGuess.of(null, "1917.m4v", null)).isEqualTo(new TitleGuess("1917", null));
    }

    @Test
    void theFileNameYearBacksUpAnEmbeddedTitleWithoutOne() {
        assertThat(TitleGuess.of("Heat", "Heat (1995).m4v", 2001)).isEqualTo(new TitleGuess("Heat", 1995));
    }

    @Test
    void theDateTagYearIsTheLastResort() {
        assertThat(TitleGuess.of("Heat", "heat.m4v", 1995)).isEqualTo(new TitleGuess("Heat", 1995));
        assertThat(TitleGuess.of(null, "Heat.m4v", 1995)).isEqualTo(new TitleGuess("Heat", 1995));
    }

    @Test
    void aTitleYearBeatsTheDateTag() {
        assertThat(TitleGuess.of(null, "Heat (1995).m4v", 2020).year()).isEqualTo(1995);
    }

    @Test
    void dotsAndUnderscoresBecomeSpacesWhenThereAreNoSpaces() {
        assertThat(TitleGuess.of(null, "The.Wicker.Man.mkv", null).title()).isEqualTo("The Wicker Man");
        assertThat(TitleGuess.of(null, "The_Wicker_Man.mkv", null).title()).isEqualTo("The Wicker Man");
        assertThat(TitleGuess.of(null, "Mr. Smith Goes to Washington.m4v", null).title())
            .isEqualTo("Mr. Smith Goes to Washington");
    }

    @Test
    void aNameWithNoExtensionIsUsedWhole() {
        assertThat(TitleGuess.of(null, "Heat", null).title()).isEqualTo("Heat");
    }

    @Test
    void aNameThatStartsWithADotIsNotCutAtThatDot() {
        assertThat(TitleGuess.of(null, ".m4v", null).title()).isEqualTo("m4v");
    }

    // --- the trailing year, read by hand ---

    private static TitleGuess guess(String embeddedTitle) {
        return TitleGuess.of(embeddedTitle, "x.m4v", null);
    }

    @Test
    void bracketsThatDoNotMatchEachOtherStillMarkTheYear() {
        assertThat(guess("Heat (1995]")).isEqualTo(new TitleGuess("Heat", 1995));
        assertThat(guess("Heat [1995)")).isEqualTo(new TitleGuess("Heat", 1995));
    }

    @Test
    void whitespaceAroundTheYearIsAllowedAndTrimmedFromTheTitle() {
        assertThat(guess("Heat  \t (1995) \n")).isEqualTo(new TitleGuess("Heat", 1995));
        assertThat(guess("Heat(1995)")).isEqualTo(new TitleGuess("Heat", 1995));
    }

    @Test
    void aYearThatIsNotFourAsciiDigitsInBracketsIsNotAYear() {
        assertThat(guess("Heat (199)")).isEqualTo(new TitleGuess("Heat (199)", null));
        assertThat(guess("Heat (19955)")).isEqualTo(new TitleGuess("Heat (19955)", null));
        assertThat(guess("Heat (19a5)")).isEqualTo(new TitleGuess("Heat (19a5)", null));
        String arabicIndicYear = "Heat (\u0661\u0669\u0669\u0665)";
        assertThat(guess(arabicIndicYear)).isEqualTo(new TitleGuess(arabicIndicYear, null));
        assertThat(guess("Heat 1995)")).isEqualTo(new TitleGuess("Heat 1995)", null));
        assertThat(guess("Heat {1995}")).isEqualTo(new TitleGuess("Heat {1995}", null));
        assertThat(guess("Heat (1995) x")).isEqualTo(new TitleGuess("Heat (1995) x", null));
        assertThat(guess("(1995")).isEqualTo(new TitleGuess("(1995", null));
    }

    @Test
    void anEmptyOrBlankNameHasNoTitleAndNoYear() {
        assertThat(TitleGuess.of(null, "", null)).isEqualTo(new TitleGuess("", null));
        assertThat(TitleGuess.of("  ", "   .m4v", null)).isEqualTo(new TitleGuess("", null));
    }

    @Test
    void aClosingBracketThatIsNotOneAndADigitBelowZeroAreNotAYear() {
        assertThat(guess("Heat (1995x")).isEqualTo(new TitleGuess("Heat (1995x", null));
        assertThat(guess("Heat (19-5)")).isEqualTo(new TitleGuess("Heat (19-5)", null));
    }

    @Test
    void aTitleThatIsOnlyAYearIsLeftAlone() {
        assertThat(guess("(1995)")).isEqualTo(new TitleGuess("(1995)", null));
        assertThat(guess("  \t(1995)")).isEqualTo(new TitleGuess("(1995)", null));
    }

    @Test
    void aTitleWithALineBreakInItIsNotSplit() {
        assertThat(guess("Heat\nTwo (1995)")).isEqualTo(new TitleGuess("Heat\nTwo (1995)", null));
        assertThat(guess("Heat\u2028Two (1995)")).isEqualTo(new TitleGuess("Heat\u2028Two (1995)", null));
        assertThat(guess("Heat\u0085 (1995)")).isEqualTo(new TitleGuess("Heat\u0085 (1995)", null));
        assertThat(guess("Heat\r\n (1995)")).isEqualTo(new TitleGuess("Heat", 1995));
    }

    @Test
    void aLongRunOfWhitespaceThatDoesNotEndTheTitleIsReadQuickly() {
        String spaces = " ".repeat(100_000);

        TitleGuess g = assertTimeout(Duration.ofSeconds(2), () -> guess("Heat" + spaces + "(1995) x"));

        assertThat(g.year()).isNull();
        assertThat(g.title()).isEqualTo("Heat" + spaces + "(1995) x");
    }
}
