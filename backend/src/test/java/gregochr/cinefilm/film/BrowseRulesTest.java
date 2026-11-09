package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.ZONE;
import static gregochr.cinefilm.TestData.collection;
import static gregochr.cinefilm.TestData.credit;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.person;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.collection.FilmCollection;
import gregochr.cinefilm.config.BadRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowseRulesTest {

    private long nextId = 1;

    private static Instant ago(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    private FilmView filmView(String title, int year, long addedDaysAgo, String... genres) {
        long id = nextId++;
        Film film = film(id, title, year, ago(addedDaysAgo), genres);
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film);
    }

    private FilmView withState(FilmView base, long positionMs, boolean watched, Instant lastPlayed,
                               Instant watchedAt) {
        Edition edition = base.film().getEditions().get(0);
        var state = state(edition, positionMs, watched, lastPlayed, watchedAt);
        return view(base.film(), state);
    }

    // continue watching

    @Test
    void continueWatchingListsUnfinishedFilmsNewestPlayedFirst() {
        FilmView old = withState(filmView("Moon", 2009, 100), 10 * MIN, false, ago(8), null);
        FilmView recent = withState(filmView("Heat", 1995, 100), 64 * MIN, false, ago(1), null);
        FilmView middle = withState(filmView("Arrival", 2016, 100), 30 * MIN, false, ago(4), null);
        FilmView finished = withState(filmView("Alien", 1979, 100), 0, true, ago(0), ago(0));
        FilmView never = filmView("Drive", 2011, 100);
        FilmView atStart = withState(filmView("Kes", 1969, 100), 0, false, ago(2), null);

        List<FilmView> result = BrowseRules.continueWatching(List.of(old, finished, never, recent, atStart, middle));

        assertThat(result).extracting(v -> v.film().getTitle()).containsExactly("Heat", "Arrival", "Moon");
    }

    @Test
    void aWatchedFilmRestartedAppearsInContinueWatching() {
        FilmView rewatch = withState(filmView("Alien", 1979, 100), 20 * MIN, true, ago(1), ago(30));
        assertThat(BrowseRules.continueWatching(List.of(rewatch))).hasSize(1);
    }

    @Test
    void aFilmRemovedFromContinueWatchingLeavesTheRowButKeepsItsPosition() {
        FilmView removed = withState(filmView("Moon", 2009, 100), 10 * MIN, false, ago(1), null);
        removed.states().values().forEach(s -> {
            s.setHiddenFromContinueWatching(true);
            s.setHiddenAt(ago(0));
        });
        FilmView shown = withState(filmView("Heat", 1995, 100), 64 * MIN, false, ago(2), null);

        assertThat(BrowseRules.continueWatching(List.of(removed, shown)))
            .extracting(v -> v.film().getTitle()).containsExactly("Heat");
        assertThat(removed.progress()).as("Resume from still works").isPresent();
        assertThat(removed.progress().orElseThrow().positionMs()).isEqualTo(10 * MIN);
    }

    @Test
    void onlyTheLastPlayedEditionsFlagDecidesWhetherTheFilmIsHidden() {
        Film film = film(98L, "Blade Runner", 1982, ago(900));
        Edition finalCut = edition(film, 980L, "The Final Cut", 117 * MIN, null);
        Edition theatrical = edition(film, 981L, "Theatrical Cut", 117 * MIN, null);
        var hiddenOlder = state(finalCut, 20 * MIN, false, ago(5), null);
        hiddenOlder.setHiddenFromContinueWatching(true);
        var visibleNewer = state(theatrical, 5 * MIN, false, ago(1), null);

        assertThat(BrowseRules.continueWatching(List.of(view(film, hiddenOlder, visibleNewer)))).hasSize(1);

        var hiddenNewer = state(theatrical, 5 * MIN, false, ago(1), null);
        hiddenNewer.setHiddenFromContinueWatching(true);
        var visibleOlder = state(finalCut, 20 * MIN, false, ago(5), null);

        assertThat(BrowseRules.continueWatching(List.of(view(film, visibleOlder, hiddenNewer)))).isEmpty();
    }

    // recently added

    @Test
    void recentlyAddedUsesAThirtyDayWindowNewestFirst() {
        FilmView a = filmView("A Day Ago", 2000, 1);
        FilmView edge = filmView("Exactly Thirty", 2000, 30);
        FilmView over = filmView("Thirty One", 2000, 31);
        FilmView b = filmView("Ten Days Ago", 2000, 10);

        assertThat(BrowseRules.recentlyAdded(List.of(over, b, edge, a), NOW))
            .extracting(v -> v.film().getTitle())
            .containsExactly("A Day Ago", "Ten Days Ago", "Exactly Thirty");
    }

    // not watched in years

    @Test
    void notWatchedInYearsNeedsWatchedAndLastActivityOverFiveYearsAgoOldestFirst() {
        FilmView sixYears = withState(filmView("Withnail", 1987, 900), 100 * MIN, true, ago(6 * 365 + 5), null);
        FilmView tenYears = withState(filmView("Kes", 1969, 900), 100 * MIN, true, ago(10 * 365), null);
        FilmView fourYears = withState(filmView("Moon", 2009, 900), 100 * MIN, true, ago(4 * 365), null);
        FilmView unwatchedOld = withState(filmView("Heat", 1995, 900), 10 * MIN, false, ago(8 * 365), null);
        FilmView neverTouched = filmView("Drive", 2011, 900);
        FilmView importedLongAgo = withState(filmView("Brief Encounter", 1945, 900), 0, true, null, ago(9 * 365));

        List<FilmView> result = BrowseRules.notWatchedInYears(
            List.of(sixYears, fourYears, unwatchedOld, tenYears, neverTouched, importedLongAgo), NOW, ZONE);

        assertThat(result).extracting(v -> v.film().getTitle())
            .containsExactly("Kes", "Brief Encounter", "Withnail");
    }

    @Test
    void notWatchedInYearsIsFalseWhenAnotherEditionWasPlayedRecently() {
        Film film = film(99L, "Blade Runner", 1982, ago(900));
        Edition finalCut = edition(film, 990L, "The Final Cut", 117 * MIN, null);
        Edition theatrical = edition(film, 991L, "Theatrical Cut", 117 * MIN, null);
        FilmView view = view(film, state(finalCut, 117 * MIN, true, ago(2730), ago(2730)),
            state(theatrical, 5 * MIN, false, ago(3), null));

        assertThat(BrowseRules.notWatchedInYears(List.of(view), NOW, ZONE)).isEmpty();
    }

    @Test
    void fiveYearBoundaryIsCalendarYears() {
        Instant justOver = NOW.atZone(ZONE).minusYears(5).minusDays(1).toInstant();
        Instant justUnder = NOW.atZone(ZONE).minusYears(5).plusDays(1).toInstant();
        FilmView over = withState(filmView("Over", 2000, 900), 100 * MIN, true, justOver, null);
        FilmView under = withState(filmView("Under", 2000, 900), 100 * MIN, true, justUnder, null);
        assertThat(BrowseRules.notWatchedInYears(List.of(over, under), NOW, ZONE))
            .extracting(v -> v.film().getTitle()).containsExactly("Over");
    }

    // search

    @Test
    void searchMatchesTitleActorAndDirectorCaseInsensitively() {
        FilmView heat = filmView("Heat", 1995, 100);
        credit(heat.film(), person(1L, "Michael Mann"), CreditRole.DIRECTOR, null, 0);
        credit(heat.film(), person(2L, "Robert De Niro"), CreditRole.CAST, "Neil McCauley", 1);
        FilmView taxi = filmView("Taxi Driver", 1976, 100);
        credit(taxi.film(), person(2L, "Robert De Niro"), CreditRole.CAST, "Travis Bickle", 1);
        credit(taxi.film(), person(3L, "Martin Scorsese"), CreditRole.DIRECTOR, null, 0);
        FilmView alien = filmView("Alien", 1979, 100);
        List<FilmView> all = List.of(heat, taxi, alien);

        assertThat(titles(all, "de niro")).containsExactlyInAnyOrder("Heat", "Taxi Driver");
        assertThat(titles(all, "SCORSESE")).containsExactly("Taxi Driver");
        assertThat(titles(all, "mann")).containsExactly("Heat");
        assertThat(titles(all, "alie")).containsExactly("Alien");
        assertThat(titles(all, "  heat  ")).containsExactly("Heat");
        assertThat(titles(all, "nothing like this")).isEmpty();
        assertThat(titles(all, "")).hasSize(3);
        assertThat(titles(all, null)).hasSize(3);
        assertThat(titles(all, "  ")).hasSize(3);
    }

    private List<String> titles(List<FilmView> all, String q) {
        return BrowseRules.filterAndSort(all, new LibraryQuery(q, "title", null, null, null, null)).stream()
            .map(v -> v.film().getTitle()).toList();
    }

    // sort

    @Test
    void sortsByTitleIgnoringLeadingThe() {
        List<FilmView> all = List.of(filmView("The Third Man", 1949, 1), filmView("Chinatown", 1974, 2),
            filmView("Taxi Driver", 1976, 3), filmView("Alien", 1979, 4), filmView("The Conversation", 1974, 5));
        assertThat(sorted(all, "title")).containsExactly("Alien", "Chinatown", "The Conversation", "Taxi Driver",
            "The Third Man");
        assertThat(sorted(all, "TITLE")).hasSize(5);
    }

    @Test
    void sameTitleSortsByYearAscending() {
        List<FilmView> all = List.of(filmView("Dune", 2021, 1), filmView("Dune", 1984, 2));
        assertThat(BrowseRules.filterAndSort(all, new LibraryQuery(null, "title", null, null, null, null)).stream()
            .map(v -> v.film().getYear())).containsExactly(1984, 2021);
    }

    @Test
    void sortsByYearAscendingThenTitle() {
        List<FilmView> all = List.of(filmView("Taxi Driver", 1976, 1), filmView("The Third Man", 1949, 2),
            filmView("The Conversation", 1974, 3), filmView("Chinatown", 1974, 4));
        assertThat(sorted(all, "year")).containsExactly("The Third Man", "Chinatown", "The Conversation",
            "Taxi Driver");
    }

    @Test
    void sortsByRecentlyAddedNewestFirstByDefault() {
        List<FilmView> all = List.of(filmView("Old", 1990, 300), filmView("New", 1990, 2), filmView("Mid", 1990, 40));
        assertThat(sorted(all, "added")).containsExactly("New", "Mid", "Old");
        assertThat(sorted(all, null)).containsExactly("New", "Mid", "Old");
        assertThat(sorted(all, " ")).containsExactly("New", "Mid", "Old");
    }

    @Test
    void addedTiesFallBackToTitle() {
        List<FilmView> all = List.of(filmView("Beta", 1990, 5), filmView("Alpha", 1990, 5));
        assertThat(sorted(all, "added")).containsExactly("Alpha", "Beta");
    }

    private List<String> sorted(List<FilmView> all, String sort) {
        return BrowseRules.filterAndSort(all, new LibraryQuery(null, sort, null, null, null, null)).stream()
            .map(v -> v.film().getTitle()).toList();
    }

    @Test
    void unknownSortOrShowIsABadRequest() {
        List<FilmView> all = List.of(filmView("Heat", 1995, 1));
        assertThatThrownBy(() -> sorted(all, "popularity")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> BrowseRules.filterAndSort(all,
            new LibraryQuery(null, null, null, null, null, "seen"))).isInstanceOf(BadRequestException.class);
    }

    // filters

    @Test
    void filtersByGenreDecadeCollectionAndShow() {
        FilmCollection alien = collection("Alien");
        alien.setId(7L);
        FilmView aliens = filmView("Aliens", 1986, 1, "Sci-fi", "Action");
        aliens.film().setCollection(alien);
        FilmView alienFilm = withState(filmView("Alien", 1979, 2, "Sci-fi", "Horror"), 100 * MIN, true, NOW, NOW);
        alienFilm.film().setCollection(alien);
        FilmView heat = filmView("Heat", 1995, 3, "Crime");
        FilmView chinatown = withState(filmView("Chinatown", 1974, 4, "Crime", "Mystery"), 100 * MIN, true, NOW,
            NOW);
        List<FilmView> all = List.of(aliens, alienFilm, heat, chinatown);

        assertThat(query(all, "sci-fi", null, null, null)).containsExactlyInAnyOrder("Aliens", "Alien");
        assertThat(query(all, " Crime ", null, null, null)).containsExactlyInAnyOrder("Heat", "Chinatown");
        assertThat(query(all, "", null, null, null)).hasSize(4);
        assertThat(query(all, null, 1970, null, null)).containsExactlyInAnyOrder("Alien", "Chinatown");
        assertThat(query(all, null, 1980, null, null)).containsExactly("Aliens");
        assertThat(query(all, null, null, 7L, null)).containsExactlyInAnyOrder("Aliens", "Alien");
        assertThat(query(all, null, null, 99L, null)).isEmpty();
        assertThat(query(all, null, null, null, "watched")).containsExactlyInAnyOrder("Alien", "Chinatown");
        assertThat(query(all, null, null, null, "unwatched")).containsExactlyInAnyOrder("Aliens", "Heat");
        assertThat(query(all, null, null, null, "all")).hasSize(4);
        assertThat(query(all, "Sci-fi", 1970, 7L, "watched")).containsExactly("Alien");
    }

    private List<String> query(List<FilmView> all, String genre, Integer decade, Long collection, String show) {
        return BrowseRules.filterAndSort(all, new LibraryQuery(null, "title", genre, decade, collection, show))
            .stream().map(v -> v.film().getTitle()).toList();
    }
}
