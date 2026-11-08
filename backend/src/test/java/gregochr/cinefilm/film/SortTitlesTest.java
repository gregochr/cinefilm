package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SortTitlesTest {

    @Test
    void leadingTheIsIgnoredAndCaseFolded() {
        assertThat(SortTitles.of("The Third Man")).isEqualTo("third man");
        assertThat(SortTitles.of("THE wicker man")).isEqualTo("wicker man");
    }

    @Test
    void otherTitlesAreOnlyLowerCased() {
        assertThat(SortTitles.of("Heat")).isEqualTo("heat");
        assertThat(SortTitles.of("Theatre of Blood")).isEqualTo("theatre of blood");
        assertThat(SortTitles.of("  Alien ")).isEqualTo("alien");
    }

    @Test
    void aTitleThatIsJustTheIsKept() {
        assertThat(SortTitles.of("The")).isEqualTo("the");
        assertThat(SortTitles.of("The ")).isEqualTo("the");
    }

    @Test
    void sortKeyOrdersTheThirdManAmongTheTs() {
        assertThat(SortTitles.of("The Third Man")).isLessThan(SortTitles.of("Titanic"));
        assertThat(SortTitles.of("The Conversation")).isLessThan(SortTitles.of("Drive"))
            .isGreaterThan(SortTitles.of("Chinatown"));
    }
}
