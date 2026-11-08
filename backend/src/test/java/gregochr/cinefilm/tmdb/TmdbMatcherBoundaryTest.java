package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.library.TitleGuess;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The thresholds and orderings inside the matcher's scoring, pinned with hand-built hits. */
class TmdbMatcherBoundaryTest {

    private static final long MIN = 60_000L;

    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final TmdbMatcher matcher = new TmdbMatcher(tmdb);

    private static SearchHit hit(long id, String title, Integer year, double popularity) {
        return new SearchHit(id, title, title, year, popularity, "/hit-" + id + ".jpg");
    }

    private static MovieDetails details(long id, String title, Integer year, Integer runtime, String poster) {
        return new MovieDetails(id, title, title, year, null, runtime, List.of(), poster, null, 0, List.of(),
            List.of(), null);
    }

    @Test
    void theFiveCandidatesWhoseDetailsAreFetchedAreChosenByTitleYearAndPopularityTogether() {
        // Six films with the same title. Only the sixth, least popular one has the file's year, and that is what
        // must put it among the five whose runtime is then checked.
        when(tmdb.search("Same", 1990)).thenReturn(List.of(hit(1, "Same", 2001, 100), hit(2, "Same", 2002, 100),
            hit(3, "Same", 2003, 100), hit(4, "Same", 2004, 100), hit(5, "Same", 2005, 100),
            hit(6, "Same", 1990, 0)));
        when(tmdb.movie(6)).thenReturn(Optional.of(details(6, "Same", 1990, 100, null)));
        for (long id = 1; id <= 5; id++) {
            when(tmdb.movie(id)).thenReturn(Optional.of(details(id, "Same", 2000 + (int) id, 100, null)));
        }

        MatchOutcome outcome = matcher.match(new TitleGuess("Same", 1990), 100 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(6);
        assertThat(outcome.candidates()).hasSize(5);
    }

    @Test
    void aCandidateShowsTheTitleAndPosterFromItsDetailsWhenTmdbGaveThem() {
        when(tmdb.search("Short", null)).thenReturn(List.of(hit(1, "Short", 1999, 0)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Short", 1999, 100, "/details.jpg")));

        MatchOutcome outcome = matcher.match(new TitleGuess("Short", null), 100 * MIN);

        assertThat(outcome.candidates()).singleElement().satisfies(c -> {
            assertThat(c.posterPath()).isEqualTo("/details.jpg");
            assertThat(c.year()).isEqualTo(1999);
            assertThat(c.runtimeMinutes()).isEqualTo(100);
        });
    }

    @Test
    void theCandidatesTitleIsTheDetailsTitleNotTheSearchHitsWhenTheyDiffer() {
        when(tmdb.search("Short", null)).thenReturn(List.of(hit(1, "Short", 1999, 0)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Short (Full Title)", 1999, 100, null)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Short", null), 100 * MIN);

        assertThat(outcome.candidates()).singleElement().satisfies(c -> {
            assertThat(c.title()).isEqualTo("Short (Full Title)");
            assertThat(c.posterPath()).isNull();
        });
    }

    @Test
    void anExactMatchOnTheLocalisedTitleAloneEndsTheSearchAfterOneRequest() {
        when(tmdb.search("Amelie", null)).thenReturn(List.of(
            new SearchHit(1, "Amelie", "Le Fabuleux Destin d'Amelie Poulain", 2001, 0, null)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Amelie", 2001, 122, null)));

        matcher.match(new TitleGuess("Amelie", null), 122 * MIN);

        verify(tmdb, times(1)).search("Amelie", null);
        verify(tmdb, times(1)).search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aSpellingDistanceOfExactlyFourAtEightyPercentSimilarityIsStillATitleMatchButFiveIsNot() {
        String query = "abcdefghijklmnopqrst";
        when(tmdb.search(query, null)).thenReturn(List.of(hit(1, query, 2000, 0),
            hit(2, "abcdefwxyzklmnopqrst", 2000, 0), hit(3, "abcdevwxyzklmnopqrst", 2000, 0)));
        when(tmdb.movie(2)).thenReturn(Optional.empty());

        MatchOutcome outcome = matcher.match(new TitleGuess(query, null), 0);

        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).containsExactly(1L, 2L);
        assertThat(outcome.candidates()).extracting(Candidate::score).containsExactly(50, 2);
    }

    @Test
    void aRuntimeIsNotComparedWhenTheFilesDurationIsUnknownEvenForAVeryShortFilm() {
        when(tmdb.search("Short", null)).thenReturn(List.of(hit(1, "Short", 2000, 0)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Short", 2000, 5, null)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Short", null), 0);

        assertThat(outcome.candidates()).singleElement().satisfies(c -> assertThat(c.score()).isEqualTo(50));
    }

    @Test
    void aTitleWhoseLastWordIsExactlySixLettersIsShortenedForTheSearch() {
        assertThat(TmdbMatcher.queries(new TitleGuess("Zzzzzz", null))).containsExactly(
            new TmdbMatcher.Query("Zzzzzz", null), new TmdbMatcher.Query("Zzzzz", null));
        assertThat(TmdbMatcher.queries(new TitleGuess("Zzzzz", null)))
            .containsExactly(new TmdbMatcher.Query("Zzzzz", null));
    }

    @Test
    void levenshteinDistanceCountsSingleCharacterEdits() {
        assertThat(TmdbMatcher.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(TmdbMatcher.distance("same", "same")).isZero();
        assertThat(TmdbMatcher.distance("", "abc")).isEqualTo(3);
        assertThat(TmdbMatcher.distance("abc", "")).isEqualTo(3);
        assertThat(TmdbMatcher.distance("xyz", "ab")).isEqualTo(3);
        assertThat(TmdbMatcher.distance("ab", "xyz")).isEqualTo(3);
        assertThat(TmdbMatcher.distance("flaw", "lawn")).isEqualTo(2);
    }
}
