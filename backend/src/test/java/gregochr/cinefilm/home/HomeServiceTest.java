package gregochr.cinefilm.home;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.FilmDtos.FilmCard;
import gregochr.cinefilm.film.FilmView;
import gregochr.cinefilm.film.LibraryLoader;
import gregochr.cinefilm.film.WeeklyTheme;
import gregochr.cinefilm.film.WeeklyThemeSelector;
import gregochr.cinefilm.home.HomeDtos.Home;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    @Mock
    private LibraryLoader loader;

    private HomeService service(Clock clock) {
        return new HomeService(loader, clock);
    }

    private long nextId = 1;

    private FilmView unwatched(String title, int year, Instant addedAt, String... genres) {
        long id = nextId++;
        Film film = film(id, title, year, addedAt, genres);
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film);
    }

    private FilmView withState(String title, int year, long positionMs, boolean watched, Instant lastPlayed,
                               Instant watchedAt) {
        long id = nextId++;
        Film film = film(id, title, year, NOW.minus(Duration.ofDays(900)));
        Edition edition = edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film, state(edition, positionMs, watched, lastPlayed, watchedAt));
    }

    /**
     * Five 1970s films of five different genres and six 1990s crime films: exactly three theme candidates (the 1970s,
     * the 1990s, Crime). Week key 202413 picks index 0 (the 1970s) and 202414 picks index 2 (Crime).
     */
    private List<FilmView> themedLibrary() {
        List<FilmView> library = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            library.add(unwatched("Seventies " + i, 1970 + i, NOW.minus(Duration.ofDays(500)), "Genre" + i));
        }
        for (int i = 0; i < 6; i++) {
            library.add(unwatched("Nineties " + i, 1990 + i, NOW.minus(Duration.ofDays(500)), "Crime"));
        }
        return library;
    }

    private void libraryIs(List<FilmView> library) {
        when(loader.loadAll()).thenReturn(library);
    }

    private static List<Film> filmsOf(List<FilmView> library) {
        return library.stream().map(FilmView::film).toList();
    }

    // the weekly theme follows the application-zone date, not the UTC date

    @Test
    void theThemeIsThatOfMondayFirstAprilBecauseLondonIsAlreadyInBritishSummerTime() {
        List<FilmView> library = themedLibrary();
        libraryIs(library);
        WeeklyTheme monday = WeeklyThemeSelector.choose(LocalDate.of(2024, 4, 1), filmsOf(library));
        WeeklyTheme sunday = WeeklyThemeSelector.choose(LocalDate.of(2024, 3, 31), filmsOf(library));
        assertThat(WeeklyThemeSelector.weekKey(LocalDate.of(2024, 4, 1))).isEqualTo(202414);
        assertThat(sunday.title()).as("the week that ended on UTC Sunday").isEqualTo("This week: the 1970s");
        assertThat(monday.title()).as("the week that began in London").isEqualTo("This week: Crime");

        Home home = service(CLOCK).home();

        assertThat(home.weeklyTheme().title()).isEqualTo(monday.title());
        assertThat(home.weeklyTheme().kind()).isEqualTo(monday.kind());
        assertThat(home.weeklyTheme().value()).isEqualTo(monday.value());
        assertThat(home.weeklyTheme().caption()).isEqualTo("A new theme every Monday");
        assertThat(home.weeklyTheme().films()).extracting(FilmCard::id)
            .containsExactlyElementsOf(monday.films().stream().map(Film::getId).toList());
    }

    @Test
    void beforeMondayAtOneUtcTheThemeIsStillLastWeeks() {
        List<FilmView> library = themedLibrary();
        libraryIs(library);
        // 2024-03-31T00:30Z is still Sunday in London (GMT until 01:00 UTC): last week's theme.
        Clock sunday = Clock.fixed(Instant.parse("2024-03-31T00:30:00Z"), CLOCK.getZone());
        WeeklyTheme expected = WeeklyThemeSelector.choose(LocalDate.of(2024, 3, 31), filmsOf(library));

        Home home = service(sunday).home();

        assertThat(home.weeklyTheme().title()).isEqualTo(expected.title());
    }

    @Test
    void anEmptyLibraryFallsBackToTheLibraryTheme() {
        libraryIs(List.of());

        Home home = service(CLOCK).home();

        assertThat(home.weeklyTheme().title()).isEqualTo("This week: your library");
        assertThat(home.weeklyTheme().films()).isEmpty();
        assertThat(home.hero()).isNull();
        assertThat(home.continueWatching()).isEmpty();
        assertThat(home.recentlyAdded()).isEmpty();
        assertThat(home.notWatchedInYears()).isEmpty();
    }

    // hero and continue watching

    @Test
    void theMostRecentlyPlayedUnfinishedFilmIsTheHeroAndTheRestFollow() {
        FilmView older = withState("Arrival", 2016, 55 * MIN, false, NOW.minus(Duration.ofDays(2)), null);
        FilmView newest = withState("Heat", 1995, 64 * MIN, false, NOW.minus(Duration.ofHours(2)), null);
        FilmView oldest = withState("Moon", 2009, 10 * MIN, false, NOW.minus(Duration.ofDays(9)), null);
        libraryIs(List.of(older, newest, oldest));

        Home home = service(CLOCK).home();

        assertThat(home.hero().title()).isEqualTo("Heat");
        assertThat(home.hero().progress().positionMs()).isEqualTo(64 * MIN);
        assertThat(home.continueWatching()).extracting(FilmCard::title).containsExactly("Arrival", "Moon");
    }

    @Test
    void aSingleFilmInProgressIsTheHeroAndLeavesContinueWatchingEmpty() {
        libraryIs(List.of(withState("Heat", 1995, 64 * MIN, false, NOW.minusSeconds(5), null)));

        Home home = service(CLOCK).home();

        assertThat(home.hero().title()).isEqualTo("Heat");
        assertThat(home.continueWatching()).isEmpty();
    }

    @Test
    void aFilmRemovedFromContinueWatchingLeavesTheHeroAndTheRow() {
        FilmView hidden = withState("Heat", 1995, 64 * MIN, false, NOW.minusSeconds(5), null);
        hidden.states().values().forEach(s -> s.setHiddenFromContinueWatching(true));
        FilmView next = withState("Arrival", 2016, 55 * MIN, false, NOW.minus(Duration.ofDays(2)), null);
        FilmView last = withState("Moon", 2009, 10 * MIN, false, NOW.minus(Duration.ofDays(9)), null);
        libraryIs(List.of(hidden, next, last));

        Home home = service(CLOCK).home();

        assertThat(home.hero().title()).isEqualTo("Arrival");
        assertThat(home.continueWatching()).extracting(FilmCard::title).containsExactly("Moon");
    }

    @Test
    void whenEveryFilmInProgressIsRemovedThereIsNoHeroAndNoRow() {
        FilmView hidden = withState("Heat", 1995, 64 * MIN, false, NOW.minusSeconds(5), null);
        hidden.states().values().forEach(s -> s.setHiddenFromContinueWatching(true));
        libraryIs(List.of(hidden));

        Home home = service(CLOCK).home();

        assertThat(home.hero()).isNull();
        assertThat(home.continueWatching()).isEmpty();
    }

    // rows measured against the injected clock

    @Test
    void recentlyAddedIncludesExactlyThirtyDaysAgoAndNothingOlder() {
        FilmView atTheEdge = unwatched("Edge", 2000, NOW.minus(Duration.ofDays(30)));
        FilmView justOutside = unwatched("Outside", 2001, NOW.minus(Duration.ofDays(30)).minusMillis(1));
        FilmView fresh = unwatched("Fresh", 2002, NOW.minusSeconds(1));
        libraryIs(List.of(atTheEdge, justOutside, fresh));

        Home home = service(CLOCK).home();

        assertThat(home.recentlyAdded()).extracting(FilmCard::title).containsExactly("Fresh", "Edge");
    }

    @Test
    void notWatchedInYearsUsesFiveCalendarYearsInTheApplicationZone() {
        // Five years before 2024-04-01 00:30 BST is 2019-04-01 00:30 BST, i.e. 2019-03-31T23:30:00Z.
        FilmView justBefore = withState("Before", 2000, 0, true, Instant.parse("2019-03-31T23:29:59Z"), null);
        FilmView atTheCutoff = withState("Cutoff", 2001, 0, true, Instant.parse("2019-03-31T23:30:00Z"), null);
        libraryIs(List.of(justBefore, atTheCutoff));

        Home home = service(CLOCK).home();

        assertThat(home.notWatchedInYears()).extracting(FilmCard::title).containsExactly("Before");
    }
}
