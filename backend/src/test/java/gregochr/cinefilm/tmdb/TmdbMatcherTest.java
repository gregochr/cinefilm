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

/** The matching scenarios from the plan, run against responses recorded from the live TMDB API. */
class TmdbMatcherTest {

    private static final long MIN = 60_000L;

    private final TmdbFixtures fixtures = new TmdbFixtures();
    private final TmdbMatcher matcher = new TmdbMatcher(fixtures.client());

    private MatchOutcome match(String title, Integer year, long minutes) {
        return matcher.match(new TitleGuess(title, year), minutes * MIN);
    }

    @Test
    void anExactTitleWithTheRightYearAndRuntimeIsMatchedAtOnceWithOneSearch() {
        MatchOutcome outcome = match("Heat", 1995, 170);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(949);
        assertThat(outcome.movie().directors()).extracting(TmdbModels.Credit::name).containsExactly("Michael Mann");
        assertThat(outcome.candidates().get(0).tmdbId()).isEqualTo(949);
        assertThat(fixtures.searches()).containsExactly("Heat 1995");
    }

    @Test
    void anExactTitleAndYearAloneIsEnoughWhenTheRuntimeIsUnknown() {
        MatchOutcome outcome = matcher.match(new TitleGuess("10 Cloverfield Lane", 2016), 0);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(333371);
    }

    @Test
    void yearDisambiguatesSameTitledFilmsEvenWhenTheRuntimeIsUnhelpful() {
        // Both Dunes are in the same search; the year in the file name picks one, a runtime of 100 min fits neither.
        assertThat(match("Dune", 1984, 100).movie().id()).isEqualTo(841);
        assertThat(match("Dune", 2021, 100).movie().id()).isEqualTo(438631);
    }

    @Test
    void aYearOneOffStillCountsButTwoOffDoesNot() {
        // The Wicker Man is 1973. With nothing else to go on (60 min fits neither film) a file year of 1974 is near
        // enough to pick it (50 + 15 against 50), but 1975 is not.
        MatchOutcome near = match("The Wicker Man", 1974, 60);
        MatchOutcome far = match("The Wicker Man", 1975, 60);

        assertThat(near.status()).isEqualTo(Status.MATCHED);
        assertThat(near.movie().id()).isEqualTo(16307);
        assertThat(far.status()).isEqualTo(Status.NEEDS_MATCH);
    }

    @Test
    void runtimeDisambiguatesTheWickerManOf1973From2006() {
        // File: "The Wicker Man", no year, 1h28m. TMDB says 1973 is 93 min and 2006 is 102 min.
        MatchOutcome outcome = match("The Wicker Man", null, 88);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(16307);
        assertThat(outcome.movie().year()).isEqualTo(1973);
        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).startsWith(16307L, 9708L);
        assertThat(outcome.candidates().get(0).score()).isGreaterThanOrEqualTo(TmdbMatcher.ACCEPT_SCORE);
    }

    @Test
    void theSameFilmAtTheOtherRuntimeIsThe2006Remake() {
        MatchOutcome outcome = match("The Wicker Man", null, 102);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(9708);
    }

    @Test
    void theRuntimeToleranceIsEightMinutesInclusiveToTheMillisecond() {
        // 1973 is 93 min and 2006 is 102 min, so 85..93 min fits only the 1973 film and 102..110 only the 2006 one.
        TitleGuess guess = new TitleGuess("The Wicker Man", null);

        assertThat(matcher.match(guess, 85 * MIN).movie().id()).isEqualTo(16307);
        assertThat(matcher.match(guess, 85 * MIN - 1).status()).as("8 min and 1 ms from 93 min")
            .isEqualTo(Status.NEEDS_MATCH);
        assertThat(matcher.match(guess, 110 * MIN).movie().id()).isEqualTo(9708);
        assertThat(matcher.match(guess, 110 * MIN + 1).status()).as("8 min and 1 ms from 102 min")
            .isEqualTo(Status.NEEDS_MATCH);
    }

    @Test
    void theOwnersMisspellingOf101DalmatiansFindsTheFilmThroughAShortenedSearch() {
        MatchOutcome outcome = match("101 Dalmations", null, 103);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(11674);
        assertThat(outcome.movie().title()).isEqualTo("101 Dalmatians");
        assertThat(fixtures.searches()).containsExactly("101 Dalmations", "101", "101 Dalmati");
    }

    @Test
    void theMisspellingIsNotMatchedWhenTheRuntimeIsWrongSoItIsLeftForTheOwner() {
        // The owner's "101 Dalmations Cartoon" is the 1961 animation (79 min); TMDB lists it under another title,
        // so the only near title is the 1996 live action at 103 min. A spelling-distance title match without a
        // runtime or year agreeing is not trusted.
        MatchOutcome outcome = match("101 Dalmations Cartoon", null, 79);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.movie()).isNull();
        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).contains(11674L);
        assertThat(outcome.candidates().get(0).score()).isLessThan(TmdbMatcher.ACCEPT_SCORE);
        assertThat(fixtures.searches()).startsWith("101 Dalmations Cartoon", "101 Dalmations");
    }

    @Test
    void aCartoonAndItsLiveActionRemakeAreToldApartByRuntime() {
        // Aladdin: the 1992 animation is 91 min, the 2019 live action 127 min. The file name has no year.
        MatchOutcome cartoon = match("Aladdin", null, 90);
        MatchOutcome liveAction = match("Aladdin", null, 128);

        assertThat(cartoon.status()).isEqualTo(Status.MATCHED);
        assertThat(cartoon.movie().id()).isEqualTo(812);
        assertThat(cartoon.movie().year()).isEqualTo(1992);
        assertThat(liveAction.status()).isEqualTo(Status.MATCHED);
        assertThat(liveAction.movie().id()).isEqualTo(420817);
        assertThat(liveAction.movie().year()).isEqualTo(2019);
    }

    @Test
    void aTrailingQualifierIsDroppedWhenTheFullNameFindsNothing() {
        MatchOutcome outcome = match("Aladdin Cartoon", null, 90);

        assertThat(outcome.status()).isEqualTo(Status.MATCHED);
        assertThat(outcome.movie().id()).isEqualTo(812);
        assertThat(fixtures.searches()).startsWith("Aladdin Cartoon", "Aladdin");
    }

    @Test
    void anAmbiguousTitleWithNothingToTellTheCandidatesApartNeedsAMatchWithItsTopFive() {
        // "Dune" with no year and a 60 minute file: two exact titles, neither agrees on runtime.
        MatchOutcome outcome = match("Dune", null, 60);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.movie()).isNull();
        assertThat(outcome.candidates()).hasSizeLessThanOrEqualTo(TmdbMatcher.MAX_CANDIDATES);
        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).contains(841L, 438631L);
        assertThat(outcome.candidates()).extracting(Candidate::score).isSortedAccordingTo(
            java.util.Comparator.reverseOrder());
        Candidate top = outcome.candidates().get(0);
        assertThat(top.title()).isEqualTo("Dune");
        assertThat(top.runtimeMinutes()).isNotNull();
        assertThat(top.posterPath()).isNotBlank();
    }

    @Test
    void twoCandidatesThatBothAgreeAreNotSeparatedAutomatically() {
        // Both Dunes at once: 2021 is 155 min, and a year-less 136-155 split does not exist, so use a runtime that
        // fits both within tolerance of neither: 146 min is 10 from each.
        assertThat(match("Dune", null, 146).status()).isEqualTo(Status.NEEDS_MATCH);
    }

    @Test
    void aTitleTmdbHasNeverHeardOfHasNoCandidates() {
        MatchOutcome outcome = match("Qzxv Nonexistent Film", null, 90);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).isEmpty();
    }

    @Test
    void anExactTitleAloneWithNoYearOrRuntimeToConfirmItIsNotAccepted() {
        MatchOutcome outcome = matcher.match(new TitleGuess("10 Cloverfield Lane", null), 0);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).extracting(Candidate::tmdbId).containsExactly(333371L);
    }

    @Test
    void aTmdbOutageIsReportedAsUnavailableNotThrown() {
        TmdbApi down = mock(TmdbApi.class);
        when(down.search("Heat", 1995)).thenThrow(new TmdbUnavailableException("down", null));

        MatchOutcome outcome = new TmdbMatcher(down).match(new TitleGuess("Heat", 1995), 170 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(outcome.movie()).isNull();
        assertThat(outcome.candidates()).isEmpty();
    }

    @Test
    void anOutageWhileFetchingTheCandidatesDetailsIsUnavailableToo() {
        TmdbApi flaky = mock(TmdbApi.class);
        when(flaky.search("Heat", 1995)).thenReturn(List.of(new SearchHit(949, "Heat", "Heat", 1995, 17, null)));
        when(flaky.movie(949)).thenThrow(new TmdbUnavailableException("down", null));

        assertThat(new TmdbMatcher(flaky).match(new TitleGuess("Heat", 1995), 0).status())
            .isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void aCandidateTmdbCannotDescribeIsKeptButNeverAutoMatched() {
        TmdbApi sparse = mock(TmdbApi.class);
        when(sparse.search("Heat", 1995)).thenReturn(List.of(new SearchHit(949, "Heat", "Heat", 1995, 17, "/p.jpg")));
        when(sparse.movie(949)).thenReturn(Optional.empty());

        MatchOutcome outcome = new TmdbMatcher(sparse).match(new TitleGuess("Heat", 1995), 170 * MIN);

        assertThat(outcome.status()).isEqualTo(Status.NEEDS_MATCH);
        assertThat(outcome.candidates()).singleElement().satisfies(c -> {
            assertThat(c.tmdbId()).isEqualTo(949);
            assertThat(c.title()).isEqualTo("Heat");
            assertThat(c.year()).isEqualTo(1995);
            assertThat(c.runtimeMinutes()).isNull();
            assertThat(c.posterPath()).isEqualTo("/p.jpg");
        });
    }

    @Test
    void accentsPunctuationAmpersandsAndALeadingTheAreIgnoredWhenComparingTitles() {
        assertThat(TmdbMatcher.normalise("The Amélie")).isEqualTo("amelie");
        assertThat(TmdbMatcher.normalise("Tom & Jerry: The Movie!")).isEqualTo("tom and jerry the movie");
        assertThat(TmdbMatcher.normalise("  Alien³ ")).isEqualTo("alien3");
        assertThat(TmdbMatcher.normalise(null)).isEmpty();
        assertThat(TmdbMatcher.normalise("Theatre")).isEqualTo("theatre");
    }

    @Test
    void theSearchesTriedForATitleAreTheFullTitleThenWithoutYearThenShorterVariants() {
        assertThat(TmdbMatcher.queries(new TitleGuess("101 Dalmations Cartoon", 1961))).containsExactly(
            new TmdbMatcher.Query("101 Dalmations Cartoon", 1961),
            new TmdbMatcher.Query("101 Dalmations Cartoon", null),
            new TmdbMatcher.Query("101 Dalmations", null), new TmdbMatcher.Query("101 Dalmations Carto", null),
            new TmdbMatcher.Query("101 Dalmati", null));
        assertThat(TmdbMatcher.queries(new TitleGuess("Heat", null)))
            .containsExactly(new TmdbMatcher.Query("Heat", null));
        assertThat(TmdbMatcher.queries(new TitleGuess("Cloverfield Lane", null))).containsExactly(
            new TmdbMatcher.Query("Cloverfield Lane", null), new TmdbMatcher.Query("Cloverfield", null),
            new TmdbMatcher.Query("Cloverfi", null));
    }

    @Test
    void aSingleShortLastWordIsNotTruncated() {
        assertThat(TmdbMatcher.queries(new TitleGuess("Alien Five", null))).containsExactly(
            new TmdbMatcher.Query("Alien Five", null), new TmdbMatcher.Query("Alien", null));
    }

    @Test
    void theTokenNeverAppearsInAnythingTheMatcherReturns() {
        MatchOutcome outcome = match("Heat", 1995, 170);

        assertThat(outcome.toString()).doesNotContain(TmdbFixtures.TOKEN);
        assertThat(fixtures.requests()).noneMatch(r -> r.contains(TmdbFixtures.TOKEN));
    }

    @Test
    void moviesAreFetchedAtMostOncePerIdAcrossMatches() {
        match("The Wicker Man", null, 88);
        long first = fixtures.requests().stream().filter(r -> r.startsWith("/3/movie/")).count();
        match("The Wicker Man", null, 102);
        long second = fixtures.requests().stream().filter(r -> r.startsWith("/3/movie/")).count();

        assertThat(first).isPositive();
        assertThat(second).as("the second match refetched no film").isEqualTo(first);
        MovieDetails cached = fixtures.client().movie(16307).orElseThrow();
        assertThat(cached.runtimeMinutes()).isEqualTo(93);
    }
}
