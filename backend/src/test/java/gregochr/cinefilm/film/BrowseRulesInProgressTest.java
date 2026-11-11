package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.MIN;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.edition;
import static gregochr.cinefilm.TestData.film;
import static gregochr.cinefilm.TestData.state;
import static gregochr.cinefilm.TestData.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The in-progress rule, its minimum watch time, the exclusive Show filters and the Last watched sort. */
class BrowseRulesInProgressTest {

    private static final long FIVE_MIN = 5 * MIN;

    private long nextId = 1;

    private static Instant ago(long days) {
        return NOW.minus(Duration.ofDays(days));
    }

    /** A one-edition film in the given state; a null {@code lastPlayed} means it was never played. */
    private FilmView played(String title, long positionMs, boolean watched, Instant lastPlayed) {
        long id = nextId++;
        Film film = film(id, title, 2000, ago(900));
        Edition edition = edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film, state(edition, positionMs, watched, lastPlayed, watched ? ago(30) : null));
    }

    private FilmView unplayed(String title) {
        long id = nextId++;
        Film film = film(id, title, 2000, ago(900));
        edition(film, id * 10, "Theatrical Cut", 100 * MIN, null);
        return view(film);
    }

    private static List<String> titles(List<FilmView> views) {
        return views.stream().map(v -> v.film().getTitle()).toList();
    }

    private static List<FilmView> show(List<FilmView> all, String show, long minMs) {
        return BrowseRules.filterAndSort(all, new LibraryQuery(null, "title", null, null, show), minMs);
    }

    // the threshold

    @Test
    void aPositionJustUnderTheMinimumIsNotInProgressAndTheMinimumItselfAndAboveAre() {
        assertThat(BrowseRules.inProgress(played("Under", FIVE_MIN - 1, false, ago(1)), FIVE_MIN)).isFalse();
        assertThat(BrowseRules.inProgress(played("At", FIVE_MIN, false, ago(1)), FIVE_MIN)).isTrue();
        assertThat(BrowseRules.inProgress(played("Over", FIVE_MIN + 1, false, ago(1)), FIVE_MIN)).isTrue();
    }

    @Test
    void withAMinimumOfZeroOneMillisecondCountsAndNothingNeverDoes() {
        assertThat(BrowseRules.inProgress(played("One", 1, false, ago(1)), 0)).isTrue();
        assertThat(BrowseRules.inProgress(played("Zero", 0, false, ago(1)), 0)).isFalse();
    }

    @Test
    void theLargestMinimumOfThirtyMinutesNeedsThirtyMinutes() {
        long thirty = 30 * MIN;
        assertThat(BrowseRules.inProgress(played("Under", thirty - 1, false, ago(1)), thirty)).isFalse();
        assertThat(BrowseRules.inProgress(played("At", thirty, false, ago(1)), thirty)).isTrue();
    }

    @Test
    void aFilmNeverPlayedIsNotInProgress() {
        assertThat(BrowseRules.inProgress(unplayed("Never"), 0)).isFalse();
    }

    @Test
    void aRewatchOfAWatchedFilmIsInProgressWhenItsPositionIsAtTheMinimum() {
        FilmView rewatch = played("Alien", FIVE_MIN, true, ago(1));

        assertThat(rewatch.watched()).isTrue();
        assertThat(BrowseRules.inProgress(rewatch, FIVE_MIN)).isTrue();
        assertThat(titles(BrowseRules.continueWatching(List.of(rewatch), FIVE_MIN))).containsExactly("Alien");
    }

    @Test
    void theLastPlayedEditionDecidesNotAnOlderOneInProgress() {
        Film film = film(98L, "Blade Runner", 1982, ago(900));
        Edition finalCut = edition(film, 980L, "The Final Cut", 117 * MIN, null);
        Edition theatrical = edition(film, 981L, "Theatrical Cut", 117 * MIN, null);
        var older = state(finalCut, 40 * MIN, false, ago(5), null);
        var newerPlayedToTheEnd = state(theatrical, 0, true, ago(1), ago(1));

        FilmView view = view(film, older, newerPlayedToTheEnd);

        assertThat(BrowseRules.inProgress(view, FIVE_MIN)).isFalse();
        assertThat(BrowseRules.continueWatching(List.of(view), FIVE_MIN)).isEmpty();
    }

    // Continue watching

    @Test
    void continueWatchingAppliesTheMinimumAndKeepsNewestPlayedFirst() {
        FilmView tooShort = played("Short", FIVE_MIN - 1, false, ago(0));
        FilmView old = played("Old", 10 * MIN, false, ago(8));
        FilmView recent = played("Recent", FIVE_MIN, false, ago(1));

        assertThat(titles(BrowseRules.continueWatching(List.of(tooShort, old, recent), FIVE_MIN)))
            .containsExactly("Recent", "Old");
    }

    @Test
    void aHiddenFilmIsInTheLibraryInProgressFilterButNotInContinueWatching() {
        FilmView hidden = played("Hidden", 20 * MIN, false, ago(1));
        hidden.states().values().forEach(s -> s.setHiddenFromContinueWatching(true));
        FilmView shown = played("Shown", 20 * MIN, false, ago(2));

        assertThat(titles(BrowseRules.continueWatching(List.of(hidden, shown), FIVE_MIN))).containsExactly("Shown");
        assertThat(titles(show(List.of(hidden, shown), "in-progress", FIVE_MIN)))
            .containsExactlyInAnyOrder("Hidden", "Shown");
        assertThat(BrowseRules.hiddenFromContinueWatching(hidden)).isTrue();
        assertThat(BrowseRules.hiddenFromContinueWatching(shown)).isFalse();
        assertThat(BrowseRules.hiddenFromContinueWatching(unplayed("Never"))).isFalse();
    }

    // Show

    @Test
    void aWatchedFilmBelowTheMinimumIsWatchedAndAnUnwatchedOneBelowItIsUnwatched() {
        FilmView watchedBelow = played("Watched Below", 2 * MIN, true, ago(1));
        FilmView unwatchedBelow = played("Unwatched Below", 2 * MIN, false, ago(1));

        List<FilmView> all = List.of(watchedBelow, unwatchedBelow);

        assertThat(titles(show(all, "watched", FIVE_MIN))).containsExactly("Watched Below");
        assertThat(titles(show(all, "unwatched", FIVE_MIN))).containsExactly("Unwatched Below");
        assertThat(show(all, "in-progress", FIVE_MIN)).isEmpty();
    }

    @Test
    void aRewatchIsInProgressAndNotWatched() {
        FilmView rewatch = played("Rewatch", 30 * MIN, true, ago(1));

        assertThat(titles(show(List.of(rewatch), "in-progress", FIVE_MIN))).containsExactly("Rewatch");
        assertThat(show(List.of(rewatch), "watched", FIVE_MIN)).isEmpty();
        assertThat(show(List.of(rewatch), "unwatched", FIVE_MIN)).isEmpty();
    }

    @Test
    void theThreeFiltersAreDisjointAndTheirUnionIsEveryFilm() {
        List<FilmView> all = List.of(
            unplayed("Never"),
            played("Finished", 0, true, ago(3)),
            played("Rewatching", 20 * MIN, true, ago(1)),
            played("Barely", 1 * MIN, false, ago(1)),
            played("Barely Watched", 1 * MIN, true, ago(1)),
            played("Half", 50 * MIN, false, ago(2)));

        for (long minMs : new long[] {0, FIVE_MIN, 30 * MIN}) {
            List<String> inProgress = titles(show(all, "in-progress", minMs));
            List<String> unwatched = titles(show(all, "unwatched", minMs));
            List<String> watched = titles(show(all, "watched", minMs));

            assertThat(Stream.of(inProgress, unwatched, watched).flatMap(List::stream).toList())
                .as("no film twice, none missing, at min %d", minMs)
                .containsExactlyInAnyOrderElementsOf(titles(show(all, "all", minMs)));
            assertThat(titles(show(all, "all", minMs))).hasSize(all.size());
        }
    }

    @Test
    void showIsCaseInsensitiveAndNoShowMeansAll() {
        List<FilmView> all = List.of(played("Half", 50 * MIN, false, ago(2)), unplayed("Never"));

        assertThat(titles(show(all, "In-Progress", FIVE_MIN))).containsExactly("Half");
        assertThat(titles(show(all, null, FIVE_MIN))).containsExactlyInAnyOrder("Half", "Never");
    }

    @Test
    void theErrorMessagesListTheNewValues() {
        List<FilmView> all = List.of(unplayed("Heat"));

        assertThatThrownBy(() -> show(all, "seen", FIVE_MIN)).isInstanceOf(BadRequestException.class)
            .hasMessage("show must be all, in-progress, unwatched or watched");
        assertThatThrownBy(() -> BrowseRules.filterAndSort(all,
            new LibraryQuery(null, "popularity", null, null, null), FIVE_MIN))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("sort must be title, year, added, imdb, rt or last-watched");
    }

    // Last watched

    @Test
    void lastWatchedIsNewestFirstWithNeverPlayedLastAndTiesByTitle() {
        FilmView older = played("Older", 0, true, ago(9));
        FilmView newest = played("Newest", 0, true, ago(1));
        FilmView tieB = played("Beta", 10 * MIN, false, ago(4));
        FilmView tieA = played("Alpha", 10 * MIN, false, ago(4));
        FilmView neverB = unplayed("Never B");
        FilmView neverA = unplayed("Never A");

        List<FilmView> sorted = BrowseRules.filterAndSort(List.of(older, neverB, tieB, newest, neverA, tieA),
            new LibraryQuery(null, "last-watched", null, null, null), FIVE_MIN);

        assertThat(titles(sorted))
            .containsExactly("Newest", "Alpha", "Beta", "Older", "Never A", "Never B");
    }
}
