package gregochr.cinefilm.film;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.FilmRepository.TmdbRef;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import gregochr.cinefilm.tmdb.TmdbApi;
import gregochr.cinefilm.tmdb.TmdbUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/** The keywords backfill: what it asks TMDB, what it saves and when, and that only one run is ever going. */
@ExtendWith(MockitoExtension.class)
class KeywordsRefresherTest {

    private static final TmdbRef HEAT = new TmdbRef(1L, 949L);
    private static final TmdbRef AMELIE = new TmdbRef(2L, 194L);
    private static final TmdbRef TAKEN = new TmdbRef(3L, 8681L);

    @Mock
    private TmdbApi tmdb;

    @Mock
    private FilmRepository films;

    @Mock
    private FilmKeywords store;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(KeywordsRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private KeywordsRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new KeywordsRefresher(tmdb, films, store, CLOCK, queued::add);
        logged.start();
        refresherLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Starts a run and executes what was queued, as the executor's thread would. */
    private void startAndRun() {
        when(tmdb.enabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    @Test
    void eachFilmWithoutKeywordsIsAskedAboutAndSavedWithTheClocksStamp() {
        when(films.findWithoutKeywords()).thenReturn(List.of(HEAT, AMELIE));
        when(tmdb.keywords(949)).thenReturn(Optional.of(List.of("heist", "los angeles")));
        when(tmdb.keywords(194)).thenReturn(Optional.of(List.of("paris")));
        when(store.save(1L, 949L, List.of("heist", "los angeles"), NOW)).thenReturn(true);
        when(store.save(2L, 194L, List.of("paris"), NOW)).thenReturn(true);

        startAndRun();

        verify(store).save(1L, 949L, List.of("heist", "los angeles"), NOW);
        verify(store).save(2L, 194L, List.of("paris"), NOW);
        assertThat(messages()).contains("Keywords refresh finished: 2 saved, 0 changed meanwhile, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aFilmTmdbNoLongerHasIsStampedWithNoKeywordsSoItIsNotAskedAgain() {
        when(films.findWithoutKeywords()).thenReturn(List.of(HEAT));
        when(tmdb.keywords(949)).thenReturn(Optional.empty());
        when(store.save(1L, 949L, List.of(), NOW)).thenReturn(true);

        startAndRun();

        verify(store).save(1L, 949L, List.of(), NOW);
    }

    @Test
    void aTmdbOutageStopsTheRunAndLeavesTheFilmsUnstampedForTheNextRun() {
        when(films.findWithoutKeywords()).thenReturn(List.of(HEAT, AMELIE, TAKEN));
        when(tmdb.keywords(949)).thenReturn(Optional.of(List.of("heist")));
        when(tmdb.keywords(194)).thenThrow(new TmdbUnavailableException("TMDB is down", null));
        when(store.save(1L, 949L, List.of("heist"), NOW)).thenReturn(true);

        startAndRun();

        verify(store).save(1L, 949L, List.of("heist"), NOW);
        verifyNoMoreInteractions(store);
        verify(tmdb, never()).keywords(8681);
        assertThat(messages()).contains("Keywords refresh stopped: TMDB is down",
            "Keywords refresh finished: 1 saved, 0 changed meanwhile, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aFilmRematchedWhileTmdbWasAskedIsLeftAloneAndTheRunCarriesOn() {
        when(films.findWithoutKeywords()).thenReturn(List.of(HEAT, AMELIE));
        when(tmdb.keywords(949)).thenReturn(Optional.of(List.of("heist")));
        when(tmdb.keywords(194)).thenReturn(Optional.of(List.of("paris")));
        when(store.save(1L, 949L, List.of("heist"), NOW)).thenReturn(false);
        when(store.save(2L, 194L, List.of("paris"), NOW)).thenReturn(true);

        startAndRun();

        verify(store).save(2L, 194L, List.of("paris"), NOW);
        assertThat(messages()).contains("Keywords refresh finished: 1 saved, 1 changed meanwhile, 0 failed");
    }

    @Test
    void aFilmThatFailsIsSkippedAndTheRestAreStillDone() {
        when(films.findWithoutKeywords()).thenReturn(List.of(HEAT, AMELIE));
        when(tmdb.keywords(949)).thenThrow(new IllegalStateException("bad json"));
        when(tmdb.keywords(194)).thenReturn(Optional.of(List.of("paris")));
        when(store.save(2L, 194L, List.of("paris"), NOW)).thenReturn(true);

        startAndRun();

        verify(store).save(2L, 194L, List.of("paris"), NOW);
        verifyNoMoreInteractions(store);
        assertThat(messages()).contains("Could not refresh the keywords of film 1: IllegalStateException",
            "Keywords refresh finished: 1 saved, 0 changed meanwhile, 1 failed");
    }

    @Test
    void aFailureListingTheFilmsIsLoggedAndTheServiceCanRunAgain() {
        when(films.findWithoutKeywords()).thenThrow(new IllegalStateException("database down"));

        startAndRun();

        assertThat(messages()).contains("Keywords refresh failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aScanOrImportQueuesOneRunAndASecondStartWhileItIsGoingIsRefused() {
        when(tmdb.enabled()).thenReturn(true);

        refresher.onScanFinished(new ScanFinished("admin"));
        assertThat(refresher.startDue()).isFalse();
        refresher.onPlexImportFinished(new PlexImportFinished());
        refresher.onFilmMatchFixed(new FilmMatchFixed());

        assertThat(queued).hasSize(1);
        when(films.findWithoutKeywords()).thenReturn(List.of());
        queued.get(0).run();
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aTriggerDuringARunMakesExactlyOneMoreRun() {
        KeywordsRefresher inline = synchronous();
        when(tmdb.enabled()).thenReturn(true);
        List<Boolean> started = new ArrayList<>();
        List<Integer> calls = new ArrayList<>();
        when(films.findWithoutKeywords()).thenAnswer(call -> {
            calls.add(calls.size());
            if (started.isEmpty()) {
                started.add(inline.startDue());
            }
            return List.of();
        });

        assertThat(inline.startDue()).isTrue();

        assertThat(started).containsExactly(false);
        assertThat(calls).hasSize(2);
        assertThat(inline.isRunning()).isFalse();
    }

    @Test
    void severalTriggersDuringOneRunStillMakeOnlyOneMoreRun() {
        KeywordsRefresher inline = synchronous();
        when(tmdb.enabled()).thenReturn(true);
        List<Integer> calls = new ArrayList<>();
        when(films.findWithoutKeywords()).thenAnswer(call -> {
            calls.add(calls.size());
            if (calls.size() == 1) {
                inline.startDue();
                inline.onScanFinished(new ScanFinished("admin"));
                inline.onPlexImportFinished(new PlexImportFinished());
            }
            return List.of();
        });

        inline.startDue();

        assertThat(calls).hasSize(2);
        assertThat(inline.isRunning()).isFalse();
    }

    @Test
    void aTriggerDuringTheExtraRunMakesYetAnotherAndNoTriggerMakesNoRerun() {
        KeywordsRefresher inline = synchronous();
        when(tmdb.enabled()).thenReturn(true);
        List<Integer> calls = new ArrayList<>();
        when(films.findWithoutKeywords()).thenAnswer(call -> {
            calls.add(calls.size());
            if (calls.size() <= 2) {
                inline.startDue();
            }
            return List.of();
        });

        inline.startDue();

        assertThat(calls).hasSize(3);
    }

    @Test
    void aTriggerDuringARunWhileTmdbIsOffMakesNoRerun() {
        KeywordsRefresher inline = synchronous();
        when(tmdb.enabled()).thenReturn(true, false);
        List<Boolean> started = new ArrayList<>();
        when(films.findWithoutKeywords()).thenAnswer(call -> {
            started.add(inline.startDue());
            return List.of();
        });

        assertThat(inline.startDue()).isTrue();

        assertThat(started).containsExactly(false);
        verify(films).findWithoutKeywords();
    }

    @Test
    void startUpStaysQuietWhenARunIsAlreadyGoing() {
        when(tmdb.enabled()).thenReturn(true);
        refresher.startDue();

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    @Test
    void nothingHappensWhenTmdbIsOff() {
        when(tmdb.enabled()).thenReturn(false);

        assertThat(refresher.startDue()).isFalse();

        assertThat(queued).isEmpty();
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void startUpBackfillsWhenTmdbIsOnAndSaysSoWhenItIsOff() {
        when(tmdb.enabled()).thenReturn(true);
        refresher.onStartup();
        assertThat(queued).hasSize(1);

        KeywordsRefresher off = new KeywordsRefresher(tmdb, films, store, CLOCK, queued::add);
        when(tmdb.enabled()).thenReturn(false);
        off.onStartup();

        assertThat(messages()).contains("Film keywords are off: no TMDB token is configured");
    }

    @Test
    void aRejectedExecutorGivesTheRunBackAndTheErrorReachesTheCaller() {
        KeywordsRefresher rejecting = new KeywordsRefresher(tmdb, films, store, CLOCK, r -> {
            throw new RejectedExecutionException("full");
        });
        when(tmdb.enabled()).thenReturn(true);

        assertThatThrownBy(rejecting::startDue).isInstanceOf(RejectedExecutionException.class);

        assertThat(rejecting.isRunning()).isFalse();
    }

    /** The refresher on an executor that runs the work at once, so a trigger can arrive from inside a run. */
    private KeywordsRefresher synchronous() {
        return new KeywordsRefresher(tmdb, films, store, CLOCK, Runnable::run);
    }

    @Test
    void aRunThatFinishesCleanlyIsNotIncompleteAndAnOutageOrAFailureIs() {
        when(films.findWithoutKeywords()).thenReturn(List.of(HEAT));
        when(tmdb.keywords(949)).thenThrow(new TmdbUnavailableException("TMDB is down", null));
        startAndRun();
        assertThat(refresher.lastRunIncomplete()).as("outage").isTrue();

        queued.clear();
        when(films.findWithoutKeywords()).thenReturn(List.of());
        startAndRun();
        assertThat(refresher.lastRunIncomplete()).as("clean run after it").isFalse();

        queued.clear();
        when(films.findWithoutKeywords()).thenReturn(List.of(AMELIE));
        when(tmdb.keywords(194)).thenThrow(new IllegalStateException("bad json"));
        startAndRun();
        assertThat(refresher.lastRunIncomplete()).as("a film that fails").isTrue();

        queued.clear();
        when(films.findWithoutKeywords()).thenThrow(new IllegalStateException("database down"));
        startAndRun();
        assertThat(refresher.lastRunIncomplete()).as("listing fails").isTrue();
    }

    @Test
    void isEnabledSaysWhetherATmdbTokenIsConfigured() {
        when(tmdb.enabled()).thenReturn(false, true);

        assertThat(refresher.isEnabled()).isFalse();
        assertThat(refresher.isEnabled()).isTrue();
    }
}
