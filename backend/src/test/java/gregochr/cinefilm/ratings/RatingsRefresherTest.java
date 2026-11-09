package gregochr.cinefilm.ratings;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.ratings.RatingsStore.Target;
import gregochr.cinefilm.tmdb.Sleeper;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RatingsRefresherTest {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final OmdbRatings HEAT_RATINGS =
        new OmdbRatings(new BigDecimal("8.3"), 712345, 84, "https://www.rottentomatoes.com/m/heat_1995");

    private final OmdbApi omdb = mock(OmdbApi.class);
    private final TmdbApi tmdb = mock(TmdbApi.class);
    private final FilmRepository films = mock(FilmRepository.class);
    private final RatingsStore store = mock(RatingsStore.class);
    private final InMemoryBudget budget = new InMemoryBudget();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private Instant now = NOW;
    private RatingsRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = refresher(2);
        when(omdb.enabled()).thenReturn(true);
        when(tmdb.enabled()).thenReturn(true);
        when(films.findIdsWithRatingsDueBefore(CUTOFF)).thenReturn(List.of());
    }

    /** A refresher on a clock the test can move, running its work inline. */
    private RatingsRefresher refresher(int dailyLimit) {
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZONE;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        Sleeper sleeper = sleeps::add;
        return new RatingsRefresher(omdb, tmdb, films, store, budget, sleeper, clock, Runnable::run, dailyLimit);
    }

    /** The budget table without a database: the same take/exhaust rules, kept in a map the test can share. */
    private static final class InMemoryBudget extends RatingsBudget {
        private final Map<LocalDate, Integer> used = new HashMap<>();

        InMemoryBudget() {
            super(null);
        }

        @Override
        public boolean take(LocalDate day, int limit) {
            int now = used.getOrDefault(day, 0);
            if (now >= limit) {
                return false;
            }
            used.put(day, now + 1);
            return true;
        }

        @Override
        public void exhaust(LocalDate day, int limit) {
            used.put(day, Math.max(limit, used.getOrDefault(day, 0)));
        }
    }

    private void due(Long... ids) {
        when(films.findIdsWithRatingsDueBefore(CUTOFF)).thenReturn(List.of(ids));
    }

    private void target(long id, String imdbId, Long tmdbId) {
        when(store.target(id)).thenReturn(Optional.of(new Target(imdbId, tmdbId)));
    }

    private void found(String imdbId) {
        when(omdb.lookup(imdbId)).thenReturn(OmdbResult.found(HEAT_RATINGS));
    }

    // what a film gets

    @Test
    void aFilmWithNoImdbIdAndNoTmdbIdIsStampedNoRatingsWithoutAskingOmdb() {
        due(1L);
        target(1, null, null);

        refresher.startDue();

        verify(store).saveRatings(1L, null, null, NOW);
        verifyNoInteractions(tmdb);
        verify(omdb, never()).lookup(anyString());
    }

    @Test
    void aFoundFilmGetsItsRatingsAndTheStamp() {
        due(1L);
        target(1, "tt0113277", 949L);
        found("tt0113277");
        when(store.saveRatings(1L, "tt0113277", HEAT_RATINGS, NOW)).thenReturn(true);

        refresher.startDue();

        verify(store).saveRatings(1L, "tt0113277", HEAT_RATINGS, NOW);
        verify(tmdb, never()).imdbId(anyLong());
    }

    @Test
    void aFilmOmdbDoesNotKnowIsStampedWithNoRatingsSoItIsNotAskedAgainTomorrow() {
        due(1L);
        target(1, "tt0113277", 949L);
        when(omdb.lookup("tt0113277")).thenReturn(OmdbResult.notFound());

        refresher.startDue();

        verify(store).saveRatings(1L, "tt0113277", null, NOW);
    }

    @Test
    void aFilmThatHasGoneIsSkipped() {
        due(1L, 2L);
        when(store.target(1L)).thenReturn(Optional.empty());
        target(2, "tt0113277", 949L);
        found("tt0113277");

        refresher.startDue();

        verify(store).saveRatings(2L, "tt0113277", HEAT_RATINGS, NOW);
        verify(store, never()).saveRatings(1L, null, null, NOW);
    }

    // backfill

    @Test
    void aFilmStoredBeforeImdbIdsWereKeptGetsItsIdFromTmdbBeforeOmdbIsAsked() {
        due(1L);
        target(1, null, 949L);
        when(tmdb.imdbId(949)).thenReturn(Optional.of("tt0113277"));
        when(store.saveImdbId(1L, "tt0113277")).thenReturn(true);
        found("tt0113277");

        refresher.startDue();

        verify(store).saveImdbId(1L, "tt0113277");
        verify(omdb).lookup("tt0113277");
        verify(store).saveRatings(1L, "tt0113277", HEAT_RATINGS, NOW);
    }

    @Test
    void whenTmdbHasNoImdbIdTheFilmIsStampedNoRatingsWithoutAskingOmdb() {
        due(1L);
        target(1, null, 949L);
        when(tmdb.imdbId(949)).thenReturn(Optional.empty());

        refresher.startDue();

        verify(store).saveRatings(1L, null, null, NOW);
        verify(store, never()).saveImdbId(anyLong(), anyString());
        verify(omdb, never()).lookup(anyString());
    }

    @Test
    void anImdbIdLongerThanTheColumnIsTreatedAsNone() {
        due(1L);
        target(1, null, 949L);
        when(tmdb.imdbId(949)).thenReturn(Optional.of("tt12345678901234567"));

        refresher.startDue();

        verify(store).saveRatings(1L, null, null, NOW);
        verify(store, never()).saveImdbId(anyLong(), anyString());
    }

    @Test
    void anImdbIdAtTheColumnLimitIsKept() {
        due(1L);
        target(1, null, 949L);
        when(tmdb.imdbId(949)).thenReturn(Optional.of("tt1234567890123"));
        when(store.saveImdbId(1L, "tt1234567890123")).thenReturn(true);
        when(omdb.lookup("tt1234567890123")).thenReturn(OmdbResult.notFound());

        refresher.startDue();

        verify(store).saveImdbId(1L, "tt1234567890123");
    }

    @Test
    void withoutATmdbTokenAFilmThatNeedsBackfillingIsLeftUnstampedToTryAgain() {
        when(tmdb.enabled()).thenReturn(false);
        due(1L);
        target(1, null, 949L);

        refresher.startDue();

        verify(store, never()).saveRatings(anyLong(), any(), any(), any());
        verify(omdb, never()).lookup(anyString());
    }

    @Test
    void aTmdbOutageLeavesThoseFilmsUnstampedAndIsNotAskedAgainThisBatchButOtherFilmsCarryOn() {
        due(1L, 2L, 3L);
        target(1, null, 949L);
        target(2, null, 348L);
        target(3, "tt0090605", 679L);
        when(tmdb.imdbId(949)).thenThrow(new TmdbUnavailableException("down", null));
        found("tt0090605");

        refresher.startDue();

        verify(tmdb, never()).imdbId(348);
        verify(store, never()).saveRatings(1L, null, null, NOW);
        verify(store, never()).saveRatings(2L, null, null, NOW);
        verify(store).saveRatings(3L, "tt0090605", HEAT_RATINGS, NOW);
    }

    // the daily budget

    @Test
    void theBatchStopsWhenTheDailyBudgetIsSpentAndLeavesTheRestUnstamped() {
        due(1L, 2L, 3L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        target(3, "tt3", 3L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));

        refresher.startDue();

        verify(omdb).lookup("tt1");
        verify(omdb).lookup("tt2");
        verify(omdb, never()).lookup("tt3");
        verify(store, never()).target(4L);
        verify(store, never()).saveRatings(3L, "tt3", HEAT_RATINGS, NOW);
    }

    @Test
    void aSecondRunTheSameUtcDayAsksNothingAndTheNextUtcDayStartsAgain() {
        now = Instant.parse("2024-03-31T00:00:00Z");
        due();
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        target(3, "tt3", 3L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L, 2L));
        refresher.startDue();
        verify(omdb).lookup("tt1");
        verify(omdb).lookup("tt2");

        now = Instant.parse("2024-03-31T23:59:59.999Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(3L));
        refresher.startDue();
        verify(omdb, never()).lookup("tt3");

        now = Instant.parse("2024-04-01T00:00:00Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(3L));
        refresher.startDue();
        verify(omdb).lookup("tt3");
    }

    @Test
    void theBudgetIsCountedInUtcNotLondonTime() {
        // 23:30 UTC on 31 March is already 1 April in London (BST); a London day would have rolled over an hour ago.
        now = Instant.parse("2024-03-31T22:59:59Z");
        refresher = refresher(1);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L));
        refresher.startDue();

        now = Instant.parse("2024-03-31T23:30:00Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(2L));
        refresher.startDue();

        verify(omdb, never()).lookup("tt2");
    }

    @Test
    void filmsWithNothingToAskOmdbAreStampedEvenWhenTheBudgetIsSpent() {
        refresher = refresher(0);
        due(1L, 2L);
        target(1, null, null);
        target(2, "tt2", 2L);

        refresher.startDue();

        verify(store).saveRatings(1L, null, null, NOW);
        verify(omdb, never()).lookup(anyString());
    }

    @Test
    void theLimitErrorStopsTheBatchLeavesThatFilmUnstampedAndSpendsTheRestOfTheDay() {
        refresher = refresher(900);
        due(1L, 2L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup("tt1")).thenReturn(OmdbResult.limitReached());

        refresher.startDue();

        verify(omdb, never()).lookup("tt2");
        verify(store, never()).saveRatings(anyLong(), any(), any(),
            any());

        due(2L);
        refresher.startDue();

        verify(omdb, never()).lookup("tt2");
    }

    @Test
    void anOmdbOutageStopsTheBatchWithoutStampingOrThrowing() {
        refresher = refresher(900);
        due(1L, 2L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup("tt1")).thenThrow(new OmdbUnavailableException("OMDb could not be reached"));

        refresher.startDue();

        verify(omdb, never()).lookup("tt2");
        verify(store, never()).saveRatings(anyLong(), any(), any(),
            any());
    }

    @Test
    void theBudgetCarriesAcrossARestartTheSameUtcDay() {
        now = Instant.parse("2024-03-31T10:00:00Z");
        due(1L, 2L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L, 2L));
        refresher.startDue();
        verify(omdb).lookup("tt1");
        verify(omdb).lookup("tt2");

        RatingsRefresher restarted = refresher(2);
        target(3, "tt3", 3L);
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(3L));
        restarted.startDue();

        verify(omdb, never()).lookup("tt3");
    }

    @Test
    void aRestartOnTheNextUtcDayStartsAFreshBudget() {
        now = Instant.parse("2024-03-31T10:00:00Z");
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L));
        refresher(1).startDue();

        now = Instant.parse("2024-04-01T00:00:00Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(2L));
        refresher(1).startDue();

        verify(omdb).lookup("tt2");
    }

    @Test
    void aLimitReachedAnswerStaysSpentAcrossARestart() {
        refresher = refresher(900);
        due(1L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup("tt1")).thenReturn(OmdbResult.limitReached());
        refresher.startDue();

        due(2L);
        refresher(900).startDue();

        verify(omdb, never()).lookup("tt2");
    }

    // a film that changes under the refresh

    @Test
    void aFilmWhoseImdbIdChangedMeanwhileIsLeftUnstampedAndTheBatchCarriesOn() {
        due(1L, 2L);
        target(1, null, 949L);
        target(2, "tt2", 2L);
        when(tmdb.imdbId(949)).thenReturn(Optional.of("tt0113277"));
        when(store.saveImdbId(1L, "tt0113277")).thenReturn(false);
        found("tt2");

        refresher.startDue();

        verify(omdb, never()).lookup("tt0113277");
        verify(store, never()).saveRatings(1L, null, null, NOW);
        verify(store, never()).saveRatings(1L, "tt0113277", HEAT_RATINGS, NOW);
        verify(store).saveRatings(2L, "tt2", HEAT_RATINGS, NOW);
    }

    @Test
    void theSaveCarriesTheIdTheRefreshActedOnSoAChangedFilmCanBeRefused() {
        due(1L, 2L);
        target(1, "tt1", 1L);
        target(2, null, null);
        found("tt1");
        when(store.saveRatings(1L, "tt1", HEAT_RATINGS, NOW)).thenReturn(false);

        refresher.startDue();

        verify(store).saveRatings(1L, "tt1", HEAT_RATINGS, NOW);
        verify(store).saveRatings(2L, null, null, NOW);
    }

    // pacing

    @Test
    void omdbRequestsAreAtLeastAQuarterOfASecondApartAndTheFirstWaitsForNothing() {
        refresher = refresher(900);
        due(1L, 2L, 3L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        target(3, "tt3", 3L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));

        refresher.startDue();

        assertThat(sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(250));
    }

    @Test
    void anInterruptedPauseStopsTheBatchAndKeepsTheInterruptFlag() {
        List<Duration> asked = new ArrayList<>();
        Sleeper interrupted = duration -> {
            asked.add(duration);
            throw new InterruptedException();
        };
        refresher = new RatingsRefresher(omdb, tmdb, films, store, budget, interrupted, Clock.fixed(NOW, ZONE),
            Runnable::run, 900);
        due(1L, 2L, 3L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        when(omdb.lookup("tt1")).thenReturn(OmdbResult.found(HEAT_RATINGS));

        try {
            refresher.startDue();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(asked).hasSize(1);
        verify(omdb, never()).lookup("tt2");
        verify(store).saveRatings(1L, "tt1", HEAT_RATINGS, NOW);
    }

    // which films

    @Test
    void theDueFilmsAreThoseNeverFetchedOrFetchedMoreThanThirtyDaysAgoAndAreAskedForByTheCutoff() {
        refresher.startDue();

        verify(films).findIdsWithRatingsDueBefore(NOW.minus(Duration.ofDays(30)));
        verify(films, never()).findIdsByRatingsOldestFirst();
    }

    @Test
    void aForcedRefreshTakesEveryFilmOldestFirstIgnoringTheThirtyDayWindow() {
        when(films.findIdsByRatingsOldestFirst()).thenReturn(List.of(7L));
        target(7, "tt7", 7L);
        found("tt7");

        refresher.startForced();

        verify(films).findIdsByRatingsOldestFirst();
        verify(films, never()).findIdsWithRatingsDueBefore(CUTOFF);
        verify(store).saveRatings(7L, "tt7", HEAT_RATINGS, NOW);
    }

    @Test
    void aForcedRefreshStillRespectsTheDailyBudget() {
        when(films.findIdsByRatingsOldestFirst()).thenReturn(List.of(1L, 2L, 3L));
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        target(3, "tt3", 3L);
        when(omdb.lookup(anyString())).thenReturn(OmdbResult.found(HEAT_RATINGS));

        refresher.startForced();

        verify(omdb, never()).lookup("tt3");
    }

    // failures

    @Test
    void aFilmThatFailsToSaveDoesNotStopTheOthers() {
        due(1L, 2L);
        target(1, "tt1", 1L);
        target(2, "tt2", 2L);
        found("tt1");
        found("tt2");
        doThrow(new IllegalStateException("db")).when(store).saveRatings(1L, "tt1", HEAT_RATINGS, NOW);

        refresher.startDue();

        verify(store).saveRatings(2L, "tt2", HEAT_RATINGS, NOW);
    }

    @Test
    void aFailureReadingTheFilmListIsLoggedNotThrown() {
        when(films.findIdsWithRatingsDueBefore(CUTOFF)).thenThrow(new IllegalStateException("db down"));

        refresher.startDue();

        assertThat(refresher.isRunning()).isFalse();
    }

    // starting

    @Test
    void withoutAKeyNothingStartsAndNoRequestIsMade() {
        when(omdb.enabled()).thenReturn(false);

        assertThat(refresher.startDue()).isFalse();

        verifyNoInteractions(films);
        assertThat(refresher.enabled()).isFalse();
    }

    @Test
    void withoutAKeyAForcedRefreshIs404() {
        when(omdb.enabled()).thenReturn(false);

        assertThatThrownBy(refresher::startForced).isInstanceOf(NotFoundException.class)
            .hasMessage("Ratings need an OMDb API key");
    }

    @Test
    void onlyOneRefreshRunsAtATimeSoAForcedOneWhileRunningIs409() {
        RatingsRefresher deferred = new RatingsRefresher(omdb, tmdb, films, store, budget, sleeps::add,
            Clock.fixed(NOW, ZONE), queued::add, 900);

        assertThat(deferred.startDue()).isTrue();
        assertThat(deferred.isRunning()).isTrue();
        assertThat(deferred.startDue()).isFalse();
        assertThatThrownBy(deferred::startForced).isInstanceOf(ConflictException.class)
            .hasMessage("A ratings refresh is already running");
        assertThat(queued).hasSize(1);

        queued.get(0).run();

        assertThat(deferred.isRunning()).isFalse();
        assertThat(deferred.startDue()).isTrue();
    }

    @Test
    void aRefusedExecutorLeavesNothingRunning() {
        Executor refusing = command -> {
            throw new RejectedExecutionException("shutting down");
        };
        RatingsRefresher stopped = new RatingsRefresher(omdb, tmdb, films, store, budget, sleeps::add,
            Clock.fixed(NOW, ZONE), refusing, 900);

        assertThatThrownBy(stopped::startDue).isInstanceOf(RejectedExecutionException.class);

        assertThat(stopped.isRunning()).isFalse();
    }

    // triggers

    @Test
    void aFinishedScanStartsARefreshOfTheDueFilms() {
        refresher.onScanFinished(new ScanFinished("nightly"));

        verify(films).findIdsWithRatingsDueBefore(CUTOFF);
    }

    @Test
    void aFinishedPlexImportStartsARefreshOfTheDueFilms() {
        refresher.onPlexImportFinished(new PlexImportFinished());

        verify(films).findIdsWithRatingsDueBefore(CUTOFF);
    }

    @Test
    void aFinishedScanWithoutAKeyDoesNothing() {
        when(omdb.enabled()).thenReturn(false);

        refresher.onScanFinished(new ScanFinished("nightly"));

        verifyNoInteractions(films);
    }

    @Test
    void startUpSaysSoOnlyWhenRatingsAreOff() {
        refresher.onStartup();
        when(omdb.enabled()).thenReturn(false);
        refresher.onStartup();

        verifyNoInteractions(films);
    }
}
