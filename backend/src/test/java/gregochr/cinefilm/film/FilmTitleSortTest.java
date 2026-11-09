package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** An explicit sort title (Plex's) against the derived one. */
class FilmTitleSortTest {

    @Test
    void anExplicitSortTitleIsKeptLowerCasedAndStripped() {
        Film film = new Film();

        film.setTitleAndSort("The Godfather", "  Godfather, The ");

        assertThat(film.getTitle()).isEqualTo("The Godfather");
        assertThat(film.getSortTitle()).isEqualTo("godfather, the");
    }

    @Test
    void aNullOrBlankSortTitleFallsBackToTheDerivedKey() {
        Film film = new Film();

        film.setTitleAndSort("The Godfather", null);
        assertThat(film.getSortTitle()).isEqualTo("godfather");
        film.setTitleAndSort("The Heat", "   ");
        assertThat(film.getSortTitle()).isEqualTo("heat");
        film.setTitleAndSort("Alien", "");
        assertThat(film.getSortTitle()).isEqualTo("alien");
    }

    @Test
    void anExplicitSortTitleIsBoundedToTheColumnAfterNormalising() {
        Film film = new Film();

        film.setTitleAndSort("Heat", "  " + "A".repeat(300));
        assertThat(film.getSortTitle()).isEqualTo("a".repeat(255));

        film.setTitleAndSort("Heat", "B".repeat(255));
        assertThat(film.getSortTitle()).hasSize(255);

        film.setTitleAndSort("Heat", "C".repeat(254));
        assertThat(film.getSortTitle()).hasSize(254);
    }
}
