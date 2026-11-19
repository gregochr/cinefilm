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
                    assertThat(theme.films()).isNotEmpty().allMatch(f -> f.getCredits().stream().anyMatch(
                        c -> c.getRole() == CreditRole.DIRECTOR && c.getPerson().getName().equals(theme.value())));
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

    // holiday weeks

    private static final LocalDate HALLOWEEN_WEEK = LocalDate.of(2026, 10, 21);
    private static final LocalDate CHRISTMAS_WEEK = LocalDate.of(2026, 12, 16);

    private Film horror(int year) {
        return mk(year, null, "Horror", "Thriller");
    }

    private Film christmas(int year, long tmdbId, String... keywords) {
        Film film = mk(year, null, "Comedy");
        film.setTmdbId(tmdbId);
        film.getKeywords().addAll(List.of(keywords));
        return film;
    }

    private List<Film> christmasLibrary(int count) {
        List<Film> films = library();
        for (int i = 0; i < count; i++) {
            films.add(christmas(1980 + i, 100 + i, "christmas", "snow"));
        }
        return films;
    }

    @Test
    void halloweenWeekIsHorrorOldestFirst() {
        List<Film> library = library();
        library.add(horror(2005));
        library.add(horror(1978));
        library.add(horror(1999));
        library.add(horror(1960));
        library.add(horror(2010));

        WeeklyTheme theme = WeeklyThemeSelector.choose(HALLOWEEN_WEEK, library);

        assertThat(theme.kind()).isEqualTo("holiday");
        assertThat(theme.value()).isEqualTo("halloween");
        assertThat(theme.title()).isEqualTo("This week: Halloween");
        assertThat(theme.caption()).isEqualTo("A new theme every Monday");
        assertThat(theme.films()).extracting(Film::getYear).containsExactly(1960, 1978, 1999, 2005, 2010);
    }

    @Test
    void aHorrorFilmWithNoYearComesLastInHalloweenWeek() {
        List<Film> library = library();
        for (int i = 0; i < 5; i++) {
            library.add(horror(1970 + i));
        }
        library.add(mk(1999, null, "Horror"));
        library.get(library.size() - 1).setYear(null);

        WeeklyTheme theme = WeeklyThemeSelector.choose(HALLOWEEN_WEEK, library);

        assertThat(theme.value()).isEqualTo("halloween");
        assertThat(theme.films()).extracting(Film::getYear).containsExactly(1970, 1971, 1972, 1973, 1974, null);
    }

    @Test
    void halloweenWeekIsTheSameFromMondayToSunday() {
        List<Film> library = library();
        for (int i = 0; i < 6; i++) {
            library.add(horror(1970 + i));
        }
        for (int day = 0; day < 7; day++) {
            assertThat(WeeklyThemeSelector.choose(LocalDate.of(2026, 10, 19).plusDays(day), library).value())
                .isEqualTo("halloween");
        }
        assertThat(WeeklyThemeSelector.choose(LocalDate.of(2026, 10, 26), library).kind()).isNotEqualTo("holiday");
    }

    @Test
    void halloweenWeekWithFewerThanFiveHorrorFilmsFallsBackToTheNormalRotation() {
        List<Film> library = library();
        for (int i = 0; i < 4; i++) {
            library.add(horror(1970 + i));
        }

        WeeklyTheme theme = WeeklyThemeSelector.choose(HALLOWEEN_WEEK, library);

        assertThat(theme.kind()).isNotEqualTo("holiday");
        assertThat(theme.films().size()).isGreaterThanOrEqualTo(WeeklyThemeSelector.MIN_FILMS);
    }

    @Test
    void exactlyFiveHorrorFilmsMakeTheHolidayTheme() {
        List<Film> library = library();
        for (int i = 0; i < 5; i++) {
            library.add(horror(1970 + i));
        }
        assertThat(WeeklyThemeSelector.choose(HALLOWEEN_WEEK, library).value()).isEqualTo("halloween");
    }

    @Test
    void halloweenWeekCapsAtTwelveFilms() {
        List<Film> library = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            library.add(horror(1960 + i));
        }
        WeeklyTheme theme = WeeklyThemeSelector.choose(HALLOWEEN_WEEK, library);
        assertThat(theme.films()).hasSize(WeeklyThemeSelector.MAX_FILMS);
        assertThat(theme.films()).extracting(Film::getYear).first().isEqualTo(1960);
        assertThat(theme.films()).extracting(Film::getYear).last().isEqualTo(1971);
    }

    @Test
    void halloweenWeekIgnoresChristmasFilms() {
        List<Film> library = christmasLibrary(6);
        assertThat(WeeklyThemeSelector.choose(HALLOWEEN_WEEK, library).kind()).isNotEqualTo("holiday");
    }

    @Test
    void christmasWeekIsFilmsTaggedChristmasOldestFirst() {
        List<Film> library = christmasLibrary(5);
        library.add(horror(1990));

        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library);

        assertThat(theme.kind()).isEqualTo("holiday");
        assertThat(theme.value()).isEqualTo("christmas");
        assertThat(theme.title()).isEqualTo("This week: Christmas");
        assertThat(theme.caption()).isEqualTo("A new theme every Monday");
        assertThat(theme.films()).extracting(Film::getYear).containsExactly(1980, 1981, 1982, 1983, 1984);
    }

    @Test
    void christmasWeekWithFewerThanFiveChristmasFilmsFallsBackToTheNormalRotation() {
        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, christmasLibrary(4));
        assertThat(theme.kind()).isNotEqualTo("holiday");
    }

    @Test
    void christmasWeekCapsAtTwelveOldestFirst() {
        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, christmasLibrary(15));
        assertThat(theme.films()).hasSize(WeeklyThemeSelector.MAX_FILMS);
        assertThat(theme.films()).extracting(Film::getYear).first().isEqualTo(1980);
        assertThat(theme.films()).extracting(Film::getYear).last().isEqualTo(1991);
    }

    @Test
    void christmasFilmsHasNoCapAndHomesRowIsItsFirstTwelve() {
        List<Film> library = christmasLibrary(15);

        List<Film> all = WeeklyThemeSelector.christmasFilms(library, Set.of(), Set.of());

        assertThat(all).hasSize(15);
        assertThat(WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library).films())
            .containsExactlyElementsOf(all.subList(0, WeeklyThemeSelector.MAX_FILMS));
    }

    @Test
    void theIncludeListAddsFilmsTheKeywordMisses() {
        List<Film> library = christmasLibrary(4);
        library.add(christmas(1988, 700L));

        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library, Set.of(700L), Set.of());

        assertThat(theme.value()).isEqualTo("christmas");
        assertThat(theme.films()).extracting(Film::getTmdbId).contains(700L).hasSize(5);
        assertThat(WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library).kind()).as("without the override")
            .isNotEqualTo("holiday");
    }

    @Test
    void theExcludeListRemovesATaggedFilm() {
        List<Film> library = christmasLibrary(6);

        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library, Set.of(), Set.of(100L));

        assertThat(theme.films()).extracting(Film::getTmdbId).doesNotContain(100L).hasSize(5);
    }

    @Test
    void excludeBeatsIncludeBeatsTheKeyword() {
        List<Film> library = christmasLibrary(4);
        Film both = christmas(1970, 800L, "christmas");
        Film excludedAndIncluded = christmas(1971, 801L);
        library.add(both);
        library.add(excludedAndIncluded);
        Set<Long> both801And800 = Set.of(801L, 800L);

        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library, both801And800, both801And800);

        assertThat(theme.kind()).as("only four films are left, so the rotation applies").isNotEqualTo("holiday");
        library.add(christmas(1972, 802L, "christmas"));
        assertThat(WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library, both801And800, both801And800).films())
            .extracting(Film::getTmdbId).doesNotContain(800L, 801L).hasSize(5);
    }

    @Test
    void aFilmWithoutATmdbIdCountsOnItsKeywordAloneAndIsNotMatchedByAnyList() {
        List<Film> library = christmasLibrary(4);
        Film untagged = mk(1960, null, "Comedy");
        Film tagged = mk(1961, null, "Comedy");
        tagged.getKeywords().add("christmas");
        library.add(untagged);
        library.add(tagged);

        WeeklyTheme theme = WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library, Set.of(1L), Set.of(2L));

        assertThat(theme.films()).contains(tagged).doesNotContain(untagged).hasSize(5);
    }

    @Test
    void otherKeywordsDoNotMakeAChristmasFilm() {
        List<Film> library = library();
        for (int i = 0; i < 6; i++) {
            library.add(christmas(1980 + i, 300 + i, "christmas eve", "winter", "santa claus"));
        }
        assertThat(WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library).kind()).isNotEqualTo("holiday");
    }

    @Test
    void christmasWeekIgnoresHorrorFilms() {
        List<Film> library = library();
        for (int i = 0; i < 6; i++) {
            library.add(horror(1970 + i));
        }
        assertThat(WeeklyThemeSelector.choose(CHRISTMAS_WEEK, library).kind()).isNotEqualTo("holiday");
    }

    @Test
    void weeksOutsideTheHolidaysAreUnchangedEvenWithHolidayFilms() {
        List<Film> withHoliday = library();
        for (int i = 0; i < 6; i++) {
            Film film = christmas(1980 + i, 400 + i, "christmas");
            film.getGenres().add("Horror");
            withHoliday.add(film);
        }
        LocalDate monday = LocalDate.of(2026, 10, 26);
        for (int week = 0; week < 7; week++) {
            assertThat(WeeklyThemeSelector.choose(monday.plusWeeks(week), withHoliday).kind())
                .as("week of %s", monday.plusWeeks(week)).isNotEqualTo("holiday");
        }
    }

    @Test
    void anEmptyLibraryInAHolidayWeekIsTheLibraryTheme() {
        assertThat(WeeklyThemeSelector.choose(CHRISTMAS_WEEK, List.of()).kind()).isEqualTo("library");
        assertThat(WeeklyThemeSelector.choose(HALLOWEEN_WEEK, List.of()).kind()).isEqualTo("library");
    }

    @Test
    void anActorWithFiveFilmsIsNotADirectorTheme() {
        Person actor = person(900L, "Prolific Actor");
        List<Film> library = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Film film = mk(1960 + i * 20, "Director " + i, "Genre " + i);
            credit(film, actor, CreditRole.CAST, "Role", 1);
            library.add(film);
        }
        for (int week = 0; week < 104; week++) {
            WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.of(2026, 1, 5).plusWeeks(week), library);
            assertThat(theme.value()).isNotEqualTo("Prolific Actor");
        }
    }

    @Test
    void aDirectorThemeDoesNotIncludeFilmsWhereThePersonOnlyActed() {
        Person loach = person(901L, "Ken Loach");
        List<Film> library = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Film film = film(id++, "Directed " + i, 1950 + i * 10, NOW, "Genre " + i);
            credit(film, loach, CreditRole.DIRECTOR, null, 0);
            library.add(film);
        }
        Film acted = film(id++, "Acted In", 1999, NOW, "Other");
        credit(acted, loach, CreditRole.CAST, "Cameo", 1);
        library.add(acted);
        library.add(film(id++, "No Credits", 2001, NOW, "Other"));
        boolean sawDirectorTheme = false;
        for (int week = 0; week < 104; week++) {
            WeeklyTheme theme = WeeklyThemeSelector.choose(LocalDate.of(2026, 1, 5).plusWeeks(week), library);
            if ("director".equals(theme.kind())) {
                sawDirectorTheme = true;
                assertThat(theme.films()).hasSize(5).doesNotContain(acted);
            }
        }
        assertThat(sawDirectorTheme).isTrue();
    }
}
