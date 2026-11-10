package gregochr.cinefilm.ratings;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.TestData.ZONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.tmdb.Sleeper;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbModels.CollectionDetails;
import gregochr.cinefilm.tmdb.TmdbModels.MovieDetails;
import gregochr.cinefilm.tmdb.TmdbModels.SearchHit;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The refresher against in-memory stand-ins for the store, OMDb and TMDB, so each test asserts what became of the
 * films (their stored ids, ratings and stamps) and what was asked of the outside services, not which repository
 * methods ran.
 */
class RatingsRefresherTest {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final OmdbRatings HEAT_RATINGS =
        new OmdbRatings(new BigDecimal("8.3"), 712345, 84, "https://www.rottentomatoes.com/m/heat_1995");
    private static final OmdbRatings STALE_RATINGS =
        new OmdbRatings(new BigDecimal("7.0"), 100, 50, "https://www.rottentomatoes.com/m/old");

    private final FakeOmdb omdb = new FakeOmdb();
    private final FakeTmdb tmdb = new FakeTmdb();
    private final FilmRepository films = mock(FilmRepository.class);
    private final FakeStore store = new FakeStore();
    private final InMemoryBudget budget = new InMemoryBudget();
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(RatingsRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Instant now = NOW;
    private RatingsRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = refresher(2);
        when(films.findIdsWithRatingsDueBefore(CUTOFF)).thenReturn(List.of());
        logged.start();
        refresherLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
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

    /** One stored film: what the store keeps, so a test can read back what the refresh did to it. */
    private static final class Row {
        private String imdbId;
        private Long tmdbId;
        private OmdbRatings ratings;
        private Instant fetchedAt;
    }

    /** The ratings columns without a database, following the real store's rules for ids, stamps and refusals. */
    private static final class FakeStore extends RatingsStore {
        private final Map<Long, Row> rows = new HashMap<>();
        private final Set<Long> failing = new HashSet<>();

        FakeStore() {
            super(null);
        }

        @Override
        public Optional<Target> target(Long filmId) {
            return Optional.ofNullable(rows.get(filmId)).map(r -> new Target(r.imdbId, r.tmdbId));
        }

        @Override
        public boolean saveImdbId(Long filmId, String imdbId) {
            Row row = rows.get(filmId);
            if (row == null || row.imdbId != null) {
                return false;
            }
            row.imdbId = imdbId;
            return true;
        }

        @Override
        public boolean saveRatings(Long filmId, String expectedImdbId, OmdbRatings ratings, Instant at) {
            if (failing.contains(filmId)) {
                throw new IllegalStateException("db");
            }
            Row row = rows.get(filmId);
            if (row == null || !Objects.equals(row.imdbId, expectedImdbId)) {
                return false;
            }
            row.ratings = ratings;
            row.fetchedAt = at;
            return true;
        }
    }

    /** OMDb with scripted answers; it records every id asked, because each ask spends from the budget. */
    private static final class FakeOmdb implements OmdbApi {
        private boolean enabled = true;
        private final Map<String, OmdbResult> answers = new HashMap<>();
        private final Set<String> outages = new HashSet<>();
        private final Map<String, Runnable> whileAsking = new HashMap<>();
        private final List<String> lookups = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public OmdbResult lookup(String imdbId) {
            lookups.add(imdbId);
            Runnable meanwhile = whileAsking.get(imdbId);
            if (meanwhile != null) {
                meanwhile.run();
            }
            if (outages.contains(imdbId)) {
                throw new OmdbUnavailableException("OMDb could not be reached");
            }
            return answers.getOrDefault(imdbId, OmdbResult.notFound());
        }
    }

    /** TMDB with scripted IMDb ids; it records every film asked about. */
    private static final class FakeTmdb implements TmdbApi {
        private boolean enabled = true;
        private final Map<Long, String> imdbIds = new HashMap<>();
        private final Set<Long> down = new HashSet<>();
        private final Map<Long, Runnable> whileAsking = new HashMap<>();
        private final List<Long> asked = new ArrayList<>();

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Optional<String> imdbId(long id) {
            asked.add(id);
            Runnable meanwhile = whileAsking.get(id);
            if (meanwhile != null) {
                meanwhile.run();
            }
            if (down.contains(id)) {
                throw new TmdbUnavailableException("down", null);
            }
            return Optional.ofNullable(imdbIds.get(id));
        }

        @Override
        public List<SearchHit> search(String title, Integer year) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MovieDetails> movie(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CollectionDetails> collection(long id) {
            throw new UnsupportedOperationException();
        }
    }

    private void due(Long... ids) {
        when(films.findIdsWithRatingsDueBefore(CUTOFF)).thenReturn(List.of(ids));
    }

    /** A stored film, never fetched. */
    private Row film(long id, String imdbId, Long tmdbId) {
        Row row = new Row();
        row.imdbId = imdbId;
        row.tmdbId = tmdbId;
        store.rows.put(id, row);
        return row;
    }

    private void found(String imdbId) {
        omdb.answers.put(imdbId, OmdbResult.found(HEAT_RATINGS));
    }

    private void assertGotRatings(Row row) {
        assertThat(row.ratings).isEqualTo(HEAT_RATINGS);
        assertThat(row.fetchedAt).isEqualTo(NOW);
    }

    private void assertStampedNoRatings(Row row) {
        assertThat(row.ratings).isNull();
        assertThat(row.fetchedAt).isEqualTo(NOW);
    }

    private void assertUntouched(Row row) {
        assertThat(row.ratings).isNull();
        assertThat(row.fetchedAt).isNull();
    }

    // what a film gets

    @Test
    void aFilmWithNoImdbIdAndNoTmdbIdIsStampedNoRatingsWithoutAskingOmdb() {
        due(1L);
        Row heat = film(1, null, null);
        heat.ratings = STALE_RATINGS;

        refresher.startDue();

        assertStampedNoRatings(heat);
        assertThat(omdb.lookups).isEmpty();
        assertThat(tmdb.asked).isEmpty();
    }

    @Test
    void aFoundFilmGetsItsRatingsAndTheStamp() {
        due(1L);
        Row heat = film(1, "tt0113277", 949L);
        found("tt0113277");

        refresher.startDue();

        assertGotRatings(heat);
        assertThat(heat.imdbId).isEqualTo("tt0113277");
        assertThat(tmdb.asked).isEmpty();
    }

    @Test
    void aFilmOmdbDoesNotKnowIsStampedWithNoRatingsSoItIsNotAskedAgainTomorrow() {
        due(1L);
        Row heat = film(1, "tt0113277", 949L);
        heat.ratings = STALE_RATINGS;
        omdb.answers.put("tt0113277", OmdbResult.notFound());

        refresher.startDue();

        assertStampedNoRatings(heat);
    }

    @Test
    void aFilmThatHasGoneIsSkippedAndTheBatchCarriesOn() {
        due(1L, 2L);
        Row alien = film(2, "tt0113277", 949L);
        found("tt0113277");

        refresher.startDue();

        assertGotRatings(alien);
        assertThat(store.rows).doesNotContainKey(1L);
    }

    // backfill

    @Test
    void aFilmStoredBeforeImdbIdsWereKeptGetsItsIdFromTmdbBeforeOmdbIsAsked() {
        due(1L);
        Row heat = film(1, null, 949L);
        tmdb.imdbIds.put(949L, "tt0113277");
        found("tt0113277");

        refresher.startDue();

        assertThat(heat.imdbId).isEqualTo("tt0113277");
        assertGotRatings(heat);
        assertThat(omdb.lookups).containsExactly("tt0113277");
    }

    @Test
    void whenTmdbHasNoImdbIdTheFilmIsStampedNoRatingsWithoutAskingOmdb() {
        due(1L);
        Row heat = film(1, null, 949L);

        refresher.startDue();

        assertThat(tmdb.asked).containsExactly(949L);
        assertThat(heat.imdbId).isNull();
        assertStampedNoRatings(heat);
        assertThat(omdb.lookups).isEmpty();
    }

    @Test
    void anImdbIdOneLongerThanTheColumnIsTreatedAsNone() {
        due(1L);
        Row heat = film(1, null, 949L);
        tmdb.imdbIds.put(949L, "tt123456789012345");

        refresher.startDue();

        assertThat(heat.imdbId).isNull();
        assertStampedNoRatings(heat);
        assertThat(omdb.lookups).isEmpty();
    }

    @Test
    void anImdbIdAtTheColumnLimitIsKept() {
        due(1L);
        Row heat = film(1, null, 949L);
        tmdb.imdbIds.put(949L, "tt12345678901234");
        found("tt12345678901234");

        refresher.startDue();

        assertThat(heat.imdbId).isEqualTo("tt12345678901234");
        assertGotRatings(heat);
    }

    @Test
    void withoutATmdbTokenAFilmThatNeedsBackfillingIsLeftUnstampedToTryAgain() {
        tmdb.enabled = false;
        due(1L);
        Row heat = film(1, null, 949L);

        refresher.startDue();

        assertUntouched(heat);
        assertThat(heat.imdbId).isNull();
        assertThat(tmdb.asked).isEmpty();
        assertThat(omdb.lookups).isEmpty();
    }

    @Test
    void aTmdbOutageLeavesThoseFilmsUnstampedAndIsNotAskedAgainThisBatchButOtherFilmsCarryOn() {
        due(1L, 2L, 3L);
        Row first = film(1, null, 949L);
        Row second = film(2, null, 348L);
        Row third = film(3, "tt0090605", 679L);
        tmdb.down.add(949L);
        found("tt0090605");

        refresher.startDue();

        assertThat(tmdb.asked).containsExactly(949L);
        assertUntouched(first);
        assertUntouched(second);
        assertGotRatings(third);
    }

    // the daily budget

    @Test
    void theBatchStopsWhenTheDailyBudgetIsSpentAndLeavesTheRestUnstamped() {
        due(1L, 2L, 3L);
        Row first = film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        Row third = film(3, "tt3", 3L);
        found("tt1");
        found("tt2");
        found("tt3");

        refresher.startDue();

        assertGotRatings(first);
        assertGotRatings(second);
        assertUntouched(third);
        assertThat(omdb.lookups).containsExactly("tt1", "tt2");
    }

    @Test
    void aSecondRunTheSameUtcDayAsksNothingAndTheNextUtcDayStartsAgain() {
        now = Instant.parse("2024-03-31T00:00:00Z");
        film(1, "tt1", 1L);
        film(2, "tt2", 2L);
        Row third = film(3, "tt3", 3L);
        found("tt3");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L, 2L));
        refresher.startDue();
        assertThat(omdb.lookups).containsExactly("tt1", "tt2");

        now = Instant.parse("2024-03-31T23:59:59.999Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(3L));
        refresher.startDue();
        assertThat(third.fetchedAt).isNull();
        assertThat(omdb.lookups).containsExactly("tt1", "tt2");

        now = Instant.parse("2024-04-01T00:00:00Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(3L));
        refresher.startDue();
        assertThat(third.ratings).isEqualTo(HEAT_RATINGS);
        assertThat(third.fetchedAt).isEqualTo(now);
    }

    @Test
    void theBudgetIsCountedInUtcNotLondonTime() {
        // 23:30 UTC on 31 March is already 1 April in London (BST); a London day would have rolled over an hour ago.
        now = Instant.parse("2024-03-31T22:59:59Z");
        refresher = refresher(1);
        film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        found("tt2");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L));
        refresher.startDue();

        now = Instant.parse("2024-03-31T23:30:00Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(2L));
        refresher.startDue();

        assertThat(second.fetchedAt).isNull();
        assertThat(omdb.lookups).containsExactly("tt1");
    }

    @Test
    void filmsWithNothingToAskOmdbAreStampedEvenWhenTheBudgetIsSpent() {
        refresher = refresher(0);
        due(1L, 2L);
        Row nothing = film(1, null, null);
        Row withId = film(2, "tt2", 2L);

        refresher.startDue();

        assertStampedNoRatings(nothing);
        assertUntouched(withId);
        assertThat(omdb.lookups).isEmpty();
    }

    @Test
    void theLimitErrorStopsTheBatchLeavesThatFilmUnstampedAndSpendsTheRestOfTheDay() {
        refresher = refresher(900);
        due(1L, 2L);
        Row first = film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        omdb.answers.put("tt1", OmdbResult.limitReached());
        found("tt2");

        refresher.startDue();

        assertUntouched(first);
        assertUntouched(second);
        assertThat(omdb.lookups).containsExactly("tt1");

        due(2L);
        refresher.startDue();

        assertUntouched(second);
        assertThat(omdb.lookups).containsExactly("tt1");
    }

    @Test
    void anOmdbOutageStopsTheBatchWithoutStampingOrThrowing() {
        refresher = refresher(900);
        due(1L, 2L);
        Row first = film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        omdb.outages.add("tt1");
        found("tt2");

        refresher.startDue();

        assertUntouched(first);
        assertUntouched(second);
        assertThat(omdb.lookups).containsExactly("tt1");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void theBudgetCarriesAcrossARestartTheSameUtcDay() {
        now = Instant.parse("2024-03-31T10:00:00Z");
        film(1, "tt1", 1L);
        film(2, "tt2", 2L);
        Row third = film(3, "tt3", 3L);
        found("tt3");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L, 2L));
        refresher.startDue();
        assertThat(omdb.lookups).containsExactly("tt1", "tt2");

        RatingsRefresher restarted = refresher(2);
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(3L));
        restarted.startDue();

        assertThat(third.fetchedAt).isNull();
        assertThat(omdb.lookups).containsExactly("tt1", "tt2");
    }

    @Test
    void aRestartOnTheNextUtcDayStartsAFreshBudget() {
        now = Instant.parse("2024-03-31T10:00:00Z");
        film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        found("tt2");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(1L));
        refresher(1).startDue();

        now = Instant.parse("2024-04-01T00:00:00Z");
        when(films.findIdsWithRatingsDueBefore(now.minus(Duration.ofDays(30)))).thenReturn(List.of(2L));
        refresher(1).startDue();

        assertThat(second.ratings).isEqualTo(HEAT_RATINGS);
        assertThat(second.fetchedAt).isEqualTo(now);
    }

    @Test
    void aLimitReachedAnswerStaysSpentAcrossARestart() {
        refresher = refresher(900);
        due(1L);
        film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        found("tt2");
        omdb.answers.put("tt1", OmdbResult.limitReached());
        refresher.startDue();

        due(2L);
        refresher(900).startDue();

        assertUntouched(second);
        assertThat(omdb.lookups).containsExactly("tt1");
    }

    // a film that changes under the refresh

    @Test
    void aFilmWhoseImdbIdChangedMeanwhileIsLeftUnstampedAndTheBatchCarriesOn() {
        due(1L, 2L);
        Row first = film(1, null, 949L);
        Row second = film(2, "tt2", 2L);
        tmdb.imdbIds.put(949L, "tt0113277");
        // A scan wrote the film's own id while TMDB was being asked.
        tmdb.whileAsking.put(949L, () -> first.imdbId = "tt-from-scan");
        found("tt2");

        refresher.startDue();

        assertThat(first.imdbId).isEqualTo("tt-from-scan");
        assertUntouched(first);
        assertGotRatings(second);
        assertThat(omdb.lookups).containsExactly("tt2");
    }

    @Test
    void aResultForAFilmWhoseImdbIdChangedWhileOmdbWasAskedIsRefusedAndTheBatchCarriesOn() {
        due(1L, 2L);
        Row first = film(1, "tt1", 1L);
        Row second = film(2, null, null);
        found("tt1");
        omdb.whileAsking.put("tt1", () -> first.imdbId = "tt-from-scan");

        refresher.startDue();

        assertUntouched(first);
        assertStampedNoRatings(second);
    }

    // pacing

    @Test
    void omdbRequestsAreAtLeastAQuarterOfASecondApartAndTheFirstWaitsForNothing() {
        refresher = refresher(900);
        due(1L, 2L, 3L);
        film(1, "tt1", 1L);
        film(2, "tt2", 2L);
        film(3, "tt3", 3L);

        refresher.startDue();

        assertThat(omdb.lookups).containsExactly("tt1", "tt2", "tt3");
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
        Row first = film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        found("tt1");

        try {
            refresher.startDue();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(asked).hasSize(1);
        assertThat(omdb.lookups).containsExactly("tt1");
        assertGotRatings(first);
        assertUntouched(second);
    }

    // which films

    @Test
    void theDueFilmsAreThoseTheRepositoryNamesForTheThirtyDayCutoffNotTheOldestFirstList() {
        due(1L);
        when(films.findIdsByRatingsOldestFirst()).thenReturn(List.of(2L));
        Row due = film(1, "tt1", 1L);
        Row other = film(2, "tt2", 2L);
        found("tt1");
        found("tt2");

        refresher.startDue();

        assertGotRatings(due);
        assertUntouched(other);
    }

    @Test
    void aForcedRefreshTakesEveryFilmOldestFirstIgnoringTheThirtyDayWindow() {
        due(1L);
        when(films.findIdsByRatingsOldestFirst()).thenReturn(List.of(7L));
        Row forced = film(7, "tt7", 7L);
        Row dueOnly = film(1, "tt1", 1L);
        found("tt7");
        found("tt1");

        refresher.startForced();

        assertGotRatings(forced);
        assertUntouched(dueOnly);
    }

    @Test
    void aForcedRefreshStillRespectsTheDailyBudget() {
        when(films.findIdsByRatingsOldestFirst()).thenReturn(List.of(1L, 2L, 3L));
        Row first = film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        Row third = film(3, "tt3", 3L);
        found("tt1");
        found("tt2");
        found("tt3");

        refresher.startForced();

        assertGotRatings(first);
        assertGotRatings(second);
        assertUntouched(third);
        assertThat(omdb.lookups).containsExactly("tt1", "tt2");
    }

    // failures

    @Test
    void aFilmThatFailsToSaveDoesNotStopTheOthers() {
        due(1L, 2L);
        Row first = film(1, "tt1", 1L);
        Row second = film(2, "tt2", 2L);
        found("tt1");
        found("tt2");
        store.failing.add(1L);

        refresher.startDue();

        assertUntouched(first);
        assertGotRatings(second);
    }

    @Test
    void aFailureReadingTheFilmListIsLoggedNotThrown() {
        when(films.findIdsWithRatingsDueBefore(CUTOFF)).thenThrow(new IllegalStateException("db down"));

        refresher.startDue();

        assertThat(refresher.isRunning()).isFalse();
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .contains("Ratings refresh failed");
    }

    // starting

    @Test
    void withoutAKeyNothingStartsAndNoRequestIsMade() {
        omdb.enabled = false;
        due(1L);
        Row heat = film(1, "tt1", 1L);
        found("tt1");

        assertThat(refresher.startDue()).isFalse();

        assertUntouched(heat);
        assertThat(omdb.lookups).isEmpty();
        assertThat(refresher.enabled()).isFalse();
    }

    @Test
    void withoutAKeyAForcedRefreshIs404() {
        omdb.enabled = false;

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
    void aFinishedScanRefreshesTheDueFilms() {
        due(1L);
        Row heat = film(1, "tt1", 1L);
        found("tt1");

        refresher.onScanFinished(new ScanFinished("nightly"));

        assertGotRatings(heat);
    }

    @Test
    void aFinishedPlexImportRefreshesTheDueFilms() {
        due(1L);
        Row heat = film(1, "tt1", 1L);
        found("tt1");

        refresher.onPlexImportFinished(new PlexImportFinished());

        assertGotRatings(heat);
    }

    @Test
    void aFinishedScanWithoutAKeyDoesNothing() {
        omdb.enabled = false;
        due(1L);
        Row heat = film(1, "tt1", 1L);
        found("tt1");

        refresher.onScanFinished(new ScanFinished("nightly"));

        assertUntouched(heat);
        assertThat(omdb.lookups).isEmpty();
    }

    @Test
    void aFinishedPlexImportWithoutAKeyDoesNothing() {
        omdb.enabled = false;
        due(1L);
        Row heat = film(1, "tt1", 1L);
        found("tt1");

        refresher.onPlexImportFinished(new PlexImportFinished());

        assertUntouched(heat);
        assertThat(omdb.lookups).isEmpty();
    }

    @Test
    void startUpSaysSoOnlyWhenRatingsAreOff() {
        refresher.onStartup();
        assertThat(logged.list).isEmpty();

        omdb.enabled = false;
        refresher.onStartup();

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
            .containsExactly("Ratings are off: no OMDb API key is configured (OMDB_API_KEY)");
    }
}
