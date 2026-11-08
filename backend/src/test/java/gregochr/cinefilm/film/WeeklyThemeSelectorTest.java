package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.person;
import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.person.Person;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WeeklyThemeSelectorTest {

    private long id = 1;

    private Film mk(int year, String director, String... genres) {
        Film film = film(id++, "Film " + id, year, NOW, genres);
        if (director != null) {
            Person person = person(director.hashCode() + 0L, director);
            credit(film, person, CreditRole.DIRECTOR, null, 0);
        }
        return film;
    }

    /** Five 1970s films, five 1990s films, five sci-fi films (mixed decades) and five by one director. */
    private List<Film> library() {
        List<Film> films = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            films.add(mk(1970 + i, "Seventies Director " + i, "Drama"));
        }
        for (int i = 0; i < 5; i++) {
            films.add(mk(1990 + i, "Nineties Director " + i, "Comedy"));
        }
        for (int i = 0; i < 5; i++) {
            films.add(mk(2000 + i * 3, "Space Director " + i, "Sci-fi"));
        }
        for (int i = 0; i < 5; i++) {
            films.add(mk(1950 + i * 2, "Ken Loach", "Western"));
        }
        return films;
    }

    @Test
    void sameWeekGivesTheSameThemeFromMondayToSunday() {
        List<Film> library = library();
        LocalDate monday = LocalDate.of(2026, 11, 2);
        WeeklyTheme expected = WeeklyThemeSelector.choose(monday, library);
        for (int day = 0; day < 7; day++) {
            WeeklyTheme theme = WeeklyThemeSelector.choose(monday.plusDays(day), library);
            assertThat(theme.title()).isEqualTo(expected.title());
            assertThat(theme.films()).isEqualTo(expected.films());
        }
    }

    @Test
    void sameInputsAlwaysGiveTheSameTheme() {
        List<Film> library = library();
        LocalDate date = LocalDate.of(2026, 11, 8);
        assertThat(WeeklyThemeSelector.choose(date, library).title())
            .isEqualTo(WeeklyThemeSelector.choose(date, library).title());
    }

    @Test
    void weekKeyRollsOverOnMondayNotSunday() {
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2026, 11, 8)))
            .isEqualTo(WeeklyThemeSelector.weekKey(LocalDate.of(2026, 11, 2)));
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2026, 11, 9)))
            .isNotEqualTo(WeeklyThemeSelector.weekKey(LocalDate.of(2026, 11, 8)));
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2026, 11, 9))).isEqualTo(202646);
    }

    @Test
    void weekKeyUsesTheIsoWeekBasedYearAtNewYear() {
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2027, 1, 1))).isEqualTo(202653);
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2027, 1, 4))).isEqualTo(202701);
    }

    @Test
    void theLastMondayOfDecemberCanAlreadyBelongToNextYearsFirstWeek() {
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2024, 12, 29))).isEqualTo(202452);
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2024, 12, 30))).isEqualTo(202501);
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2025, 12, 28))).isEqualTo(202552);
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2025, 12, 29))).isEqualTo(202601);
    }

    @Test
    void aFiftyThreeWeekYearEndsOnItsOwnSundayAndTheNextYearStartsOnMonday() {
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2020, 12, 31))).isEqualTo(202053);
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2021, 1, 3))).isEqualTo(202053);
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2021, 1, 4))).isEqualTo(202101);
    }

    @Test
    void theThemeChangesAcrossWeeksAndCoversEveryKindEventually() {
        List<Film> library = library();
        Set<String> kinds = new HashSet<>();
        Set<String> titles = new HashSet<>();
        LocalDate monday = LocalDate.of(2026, 1, 5);
        for (int week = 0; week < 52; week++) {
            WeeklyTheme theme = WeeklyThemeSelector.choose(monday.plusWeeks(week), library);
            kinds.add(theme.kind());
            titles.add(theme.title());
        }
        assertThat(titles.size()).isGreaterThan(1);
        assertThat(kinds).contains("decade", "genre", "director");
    }

    @Test
    void aThemeOnlyUsesGroupsWithAtLeastFiveFilms() {
        List<Film> library = library();
        library.add(mk(1960, "Rare Director", "Noir"));
        library.add(mk(1961, "Rare Director", "Noir"));
        for (int week = 0; week < 52; week++) {
            WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.of(2026, 1, 5).plusWeeks(week), library);
            assertThat(theme.title()).doesNotContain("Noir").doesNotContain("Rare Director").doesNotContain("1960s");
            assertThat(theme.films().size()).isGreaterThanOrEqualTo(WeeklyThemeSelector.MIN_FILMS);
        }
    }

    @Test
    void titlesAndCaptionFollowTheHandoverCopy() {
        List<Film> library = library();
        boolean sawDecade = false;
        boolean sawGenre = false;
        boolean sawDirector = false;
        for (int week = 0; week < 52; week++) {
            WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.of(2026, 1, 5).plusWeeks(week), library);
            assertThat(theme.caption()).isEqualTo("A new theme every Monday");
            switch (theme.kind()) {
                case "decade" -> {
                    sawDecade = true;
                    assertThat(theme.title()).isEqualTo("This week: the " + theme.value() + "s");
                    assertThat(theme.films()).allMatch(f -> f.getYear() / 10 * 10 == Integer.parseInt(theme.value()));
                }
                case "genre" -> {
                    sawGenre = true;
                    assertThat(theme.title()).isEqualTo("This week: " + theme.value());
                    assertThat(theme.films()).allMatch(f -> f.getGenres().contains(theme.value()));
                }
                default -> {
                    sawDirector = true;
                    assertThat(theme.title()).isEqualTo("This week: films by " + theme.value());
                }
            }
        }
        assertThat(sawDecade && sawGenre && sawDirector).isTrue();
    }

    @Test
    void decadeThemeFilmsAreChronologicalAndCappedAtTwelve() {
        List<Film> library = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            library.add(mk(1970 + (i % 10), null, "Drama"));
        }
        WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.of(2026, 11, 9), library);
        assertThat(theme.films()).hasSize(WeeklyThemeSelector.MAX_FILMS);
        assertThat(theme.films()).isSortedAccordingTo(
            java.util.Comparator.comparingInt(Film::getYear).thenComparing(Film::getSortTitle));
    }

    @Test
    void aSmallLibraryFallsBackToAGenericTheme() {
        List<Film> library = List.of(mk(1970, "A", "Drama"), mk(1980, "B", "Comedy"));
        WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.of(2026, 11, 9), library);
        assertThat(theme.kind()).isEqualTo("library");
        assertThat(theme.title()).isEqualTo("This week: your library");
        assertThat(theme.films()).hasSize(2);
        assertThat(WeeklyThemeSelector.choose(LocalDate.of(2026, 11, 9), List.of()).films()).isEmpty();
    }
}
