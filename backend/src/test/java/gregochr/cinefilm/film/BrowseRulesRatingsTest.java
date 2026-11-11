package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sorting by IMDb score and by Rotten Tomatoes: highest first, films without the score last, ties by title. */
class BrowseRulesRatingsTest {

    private long nextId = 1;

    private FilmView rated(String title, String imdb, Integer rt) {
        long id = nextId++;
        Film film = film(id, title, 2000, NOW);
        film.setImdbRating(imdb == null ? null : new BigDecimal(imdb));
        film.setRtScore(rt);
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film);
    }

    private List<String> sorted(List<FilmView> all, String sort) {
        return BrowseRules.filterAndSort(all, new LibraryQuery(null, sort, null, null, null), 0).stream()
            .map(v -> v.film().getTitle()).toList();
    }

    @Test
    void imdbSortsHighestFirstWithUnratedFilmsLastAndTiesByTitle() {
        List<FilmView> all = List.of(rated("Zulu", "7.7", 90), rated("Alien", "8.5", 98), rated("Heat", "8.3", 84),
            rated("Unrated", null, 50), rated("Blade Runner", "8.3", 89), rated("Also Unrated", null, null));

        assertThat(sorted(all, "imdb")).containsExactly("Alien", "Blade Runner", "Heat", "Zulu", "Also Unrated",
            "Unrated");
    }

    @Test
    void imdbComparesTheScoreNotItsText() {
        List<FilmView> all = List.of(rated("Low", "9.0", null), rated("High", "10.0", null),
            rated("Mid", "10.0", null), rated("Tiny", "0.0", null));

        assertThat(sorted(all, "imdb")).containsExactly("High", "Mid", "Low", "Tiny");
    }

    @Test
    void rtSortsHighestFirstWithUnscoredFilmsLastAndTiesByTitle() {
        List<FilmView> all = List.of(rated("Zulu", "7.7", 90), rated("Alien", "8.5", 98), rated("Heat", "8.3", 84),
            rated("Unscored", "9.9", null), rated("Blade Runner", "8.3", 84), rated("Zero", "1.0", 0),
            rated("Perfect", null, 100));

        assertThat(sorted(all, "rt")).containsExactly("Perfect", "Alien", "Zulu", "Blade Runner", "Heat", "Zero",
            "Unscored");
    }

    @Test
    void theSortKeyIsCaseInsensitive() {
        List<FilmView> all = List.of(rated("B", "5.0", 10), rated("A", "6.0", 20));

        assertThat(sorted(all, "IMDB")).containsExactly("A", "B");
        assertThat(sorted(all, "Rt")).containsExactly("A", "B");
    }

    @Test
    void whenNoFilmHasAScoreTheOrderIsByTitle() {
        List<FilmView> all = List.of(rated("Beta", null, null), rated("Alpha", null, null));

        assertThat(sorted(all, "imdb")).containsExactly("Alpha", "Beta");
        assertThat(sorted(all, "rt")).containsExactly("Alpha", "Beta");
    }

    @Test
    void anUnknownSortSaysWhichAreValid() {
        assertThatThrownBy(() -> sorted(List.of(), "stars")).isInstanceOf(BadRequestException.class)
            .hasMessage("sort must be title, year, added, imdb, rt or last-watched");
    }
}
