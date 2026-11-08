package gregochr.cinefilm.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.library.TitleGuess;
import gregochr.cinefilm.tmdb.MatchOutcome.Candidate;
import gregochr.cinefilm.tmdb.MatchOutcome.Status;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Scoring edge cases that the recorded TMDB responses do not happen to contain, with hand-built hits. */
class TmdbMatcherEdgeTest {

    private static final long MIN = 60_000L;

    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final TmdbMatcher matcher = new TmdbMatcher(tmdb);

    private static MovieDetails details(long id, String title, Integer year, Integer runtime) {
        return new MovieDetails(id, title, title, year, null, runtime, List.of(), null, null, 0, List.of(),
            List.of(), null);
    }

    @Test
    void twoCandidatesThatBothScoreHighEnoughButAreLevelAreNotSeparatedAutomatically() {
        when(tmdb.search("Twins", 2000)).thenReturn(List.of(new SearchHit(1, "Twins", "Twins", 2000, 0, null),
            new SearchHit(2, "Twins", "Twins", 2000, 0, null)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Twins", 2000, 100)));
        when(tmdb.movie(2)).thenReturn(Optional.of(details(2, "Twins", 2000, 100)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Twins", 2000), 100 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).extracting(Candidate::score).containsExactly(100, 100);
    }

    @Test
    void aLeadOfExactlyTenPointsIsEnough() {
        // Same year and runtime fit for both, but film 2 is a year out: 100 against 90 is a lead of exactly ten.
        when(tmdb.search("Pair", 2000)).thenReturn(List.of(new SearchHit(1, "Pair", "Pair", 2000, 0, null),
            new SearchHit(2, "Pair", "Pair", 2001, 0, null)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Pair", 2000, 100)));
        when(tmdb.movie(2)).thenReturn(Optional.of(details(2, "Pair", 2001, 100)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Pair", 2000), 100 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(1);
        assertThat(outcome.candidates()).extracting(Candidate::score).containsExactly(100, 90);
    }

    @Test
    void aLeadJustUnderTenPointsIsNotEnough() {
        // As above, but the year-out film is a far more popular one: its popularity tie-break (0.67) cuts the lead
        // from exactly 10 to 9.33.
        when(tmdb.search("Pair", 2000)).thenReturn(List.of(new SearchHit(1, "Pair", "Pair", 2000, 0, null),
            new SearchHit(2, "Pair", "Pair", 2001, 100, null)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Pair", 2000, 100)));
        when(tmdb.movie(2)).thenReturn(Optional.of(details(2, "Pair", 2001, 100)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Pair", 2000), 100 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).containsExactly(1L, 2L);
    }

    @Test
    void theOriginalTitleCountsAsAnExactMatchToo() {
        when(tmdb.search("Le Fabuleux Destin", null)).thenReturn(List.of(
            new SearchHit(1, "Amelie", "Le Fabuleux Destin", 2001, 0, null)));
        when(tmdb.movie(1)).thenReturn(Optional.of(details(1, "Amelie", 2001, 122)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Le Fabuleux Destin", null), 122 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(1);
    }

    @Test
    void aHitWithoutAReleaseYearCannotEarnYearPoints() {
        when(tmdb.search("Undated", 2000)).thenReturn(List.of(new SearchHit(1, "Undated", "Undated", null, 0, null)));
        when(tmdb.movie(1)).thenReturn(Optional.empty());

        MatchOutcome outcome = matcher.match(new TitleGuess("Undated", 2000), 0);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).singleElement().satisfies(c -> {
            assertThat(c.year()).isNull();
            assertThat(c.score()).isEqualTo(50);
        });
    }

    @Test
    void aTitleThatNormalisesToNothingMatchesNothing() {
        when(tmdb.search("...", null)).thenReturn(List.of(new SearchHit(1, "???", "???", 2000, 0, null),
            new SearchHit(2, "Something", "Something", 2000, 0, null)));

        MatchOutcome outcome = matcher.match(new TitleGuess("...", null), 0);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).isEmpty();
    }

    @Test
    void aHitWithNoTitleAtAllIsNeverACandidate() {
        when(tmdb.search("Heat", null)).thenReturn(List.of(new SearchHit(1, null, null, 1995, 99, null)));

        assertThat(matcher.match(new TitleGuess("Heat", null), 0).candidates()).isEmpty();
    }

    @Test
    void aSpellingDistanceOfFiveOrMoreOrBelowEightyPercentSimilarityIsNotATitleMatch() {
        when(tmdb.search("Abcdefghij", null)).thenReturn(List.of(
            new SearchHit(1, "Abcdefghij Klmno", "Abcdefghij Klmno", 2000, 0, null),
            new SearchHit(2, "Abcdexyzij", "Abcdexyzij", 2000, 0, null),
            new SearchHit(3, "Abxxefghij", "Abxxefghij", 2000, 0, null)));
        when(tmdb.movie(3)).thenReturn(Optional.of(details(3, "Abxxefghij", 2000, 100)));

        MatchOutcome outcome = matcher.match(new TitleGuess("Abcdefghij", null), 100 * MIN);

        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).containsExactly(3L);
    }
}
