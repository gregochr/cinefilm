package gregochr.cinefilm.opensubtitles;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.film.FilmRepository;
import gregochr.cinefilm.film.FilmRepository.ImdbRef;
import gregochr.cinefilm.library.ScanFinished;
import gregochr.cinefilm.plex.PlexImportFinished;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;

/** The subtitle search run: which films it asks about, what it saves and when, and that only one run is ever going. */
@ExtendWith(MockitoExtension.class)
class OpenSubtitlesRefresherTest {

    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final ImdbRef TAKEN = new ImdbRef(1L, "tt0936501");
    private static final ImdbRef HEAT = new ImdbRef(2L, "tt0113277");
    private static final ImdbRef AMELIE = new ImdbRef(3L, "tt0211915");

    @Mock
    private OpenSubtitlesApi api;

    @Mock
    private FilmRepository films;

    @Mock
    private ApplicationEventPublisher events;

    private final List<Runnable> queued = new ArrayList<>();
    private final Logger refresherLog = (Logger) LoggerFactory.getLogger(OpenSubtitlesRefresher.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private OpenSubtitlesRefresher refresher;

    @BeforeEach
    void setUp() {
        refresher = new OpenSubtitlesRefresher(api, films, CLOCK, queued::add, events);
        logged.start();
        refresherLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        refresherLog.detachAppender(logged);
    }

    private static OpenSubtitlesResult result(long id) {
        return new OpenSubtitlesResult(id, id + 1000, "Taken.2008", 23.976, 300, true, false, null, null);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Starts a run and executes what was queued, as the executor's thread would. */
    private void startAndRun() {
        when(api.enabled()).thenReturn(true);
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(1);
        queued.get(0).run();
    }

    @Test
    void theFilmsThatAreDueAreFoundWithACutoffOfThirtyDaysBeforeTheClock() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of());

        startAndRun();

        verify(films).findOpenSubtitlesDue(CUTOFF);
        assertThat(messages()).containsExactly("Subtitle search finished: 0 saved, 0 changed meanwhile, 0 failed");
    }

    @Test
    void eachDueFilmIsSearchedAndSavedWithItsCountItsBestResultAndTheClocksStamp() {
        OpenSubtitlesResult best = result(77);
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN, HEAT));
        when(api.searchForced("tt0936501")).thenReturn(new OpenSubtitlesSearch(7, best));
        when(api.searchForced("tt0113277")).thenReturn(new OpenSubtitlesSearch(2, result(5)));
        when(films.saveOpenSubtitles(1L, "tt0936501", 7, best.toJson(), NOW)).thenReturn(1);
        when(films.saveOpenSubtitles(2L, "tt0113277", 2, result(5).toJson(), NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveOpenSubtitles(1L, "tt0936501", 7, "{\"id\":77,\"fileId\":1077,\"release\":\"Taken.2008\","
            + "\"fps\":23.976,\"downloadCount\":300,\"fromTrusted\":true,\"hearingImpaired\":false,"
            + "\"fileCount\":1}", NOW);
        verify(films).saveOpenSubtitles(2L, "tt0113277", 2, result(5).toJson(), NOW);
        assertThat(messages()).contains("Subtitle search finished: 2 saved, 0 changed meanwhile, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aSearchWithNoResultsIsSavedAsACountOfZeroAndNoBest() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN));
        when(api.searchForced("tt0936501")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitles(1L, "tt0936501", 0, null, NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveOpenSubtitles(1L, "tt0936501", 0, null, NOW);
    }

    @Test
    void theFilmsAreSearchedInTheOrderTheRepositoryGaveThem() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(AMELIE, TAKEN, HEAT));
        when(api.searchForced("tt0211915")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(api.searchForced("tt0936501")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(api.searchForced("tt0113277")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitles(3L, "tt0211915", 0, null, NOW)).thenReturn(1);
        when(films.saveOpenSubtitles(1L, "tt0936501", 0, null, NOW)).thenReturn(1);
        when(films.saveOpenSubtitles(2L, "tt0113277", 0, null, NOW)).thenReturn(1);

        startAndRun();

        InOrder order = inOrder(api);
        order.verify(api).searchForced("tt0211915");
        order.verify(api).searchForced("tt0936501");
        order.verify(api).searchForced("tt0113277");
    }

    @Test
    void anOutageStopsTheRunAndNothingAfterItIsSearchedOrStampedAndNothingIsSavedAsNoResults() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN, HEAT, AMELIE));
        when(api.searchForced("tt0936501")).thenReturn(new OpenSubtitlesSearch(1, result(5)));
        when(api.searchForced("tt0113277")).thenThrow(new OpenSubtitlesUnavailableException("OpenSubtitles is down"));
        when(films.saveOpenSubtitles(1L, "tt0936501", 1, result(5).toJson(), NOW)).thenReturn(1);

        startAndRun();

        verify(films).findOpenSubtitlesDue(CUTOFF);
        verify(films).saveOpenSubtitles(1L, "tt0936501", 1, result(5).toJson(), NOW);
        verifyNoMoreInteractions(films);
        verify(api).searchForced("tt0936501");
        verify(api).searchForced("tt0113277");
        verify(api, org.mockito.Mockito.never()).searchForced("tt0211915");
        assertThat(messages()).contains("Subtitle search stopped: OpenSubtitles is down",
            "Subtitle search finished: 1 saved, 0 changed meanwhile, 0 failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void anOutageOnTheFirstFilmSavesNothingAtAll() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN));
        when(api.searchForced("tt0936501")).thenThrow(new OpenSubtitlesUnavailableException("down"));

        startAndRun();

        verify(films).findOpenSubtitlesDue(CUTOFF);
        verifyNoMoreInteractions(films);
    }

    @Test
    void aFilmWhoseImdbIdChangedDuringTheSearchIsNotOverwrittenAndTheRunCarriesOn() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN, HEAT));
        when(api.searchForced("tt0936501")).thenReturn(new OpenSubtitlesSearch(7, result(5)));
        when(api.searchForced("tt0113277")).thenReturn(new OpenSubtitlesSearch(2, result(6)));
        // The statement matches on the IMDb id the search was for: a change makes it update no row.
        when(films.saveOpenSubtitles(1L, "tt0936501", 7, result(5).toJson(), NOW)).thenReturn(0);
        when(films.saveOpenSubtitles(2L, "tt0113277", 2, result(6).toJson(), NOW)).thenReturn(1);

        startAndRun();

        verify(films).saveOpenSubtitles(1L, "tt0936501", 7, result(5).toJson(), NOW);
        verify(films).saveOpenSubtitles(2L, "tt0113277", 2, result(6).toJson(), NOW);
        assertThat(messages()).contains("Subtitle search finished: 1 saved, 1 changed meanwhile, 0 failed");
    }

    @Test
    void aFilmThatFailsIsSkippedAndTheRestAreStillDone() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN, HEAT, AMELIE));
        when(api.searchForced("tt0936501")).thenThrow(new IllegalArgumentException("Not an IMDb id"));
        when(api.searchForced("tt0113277")).thenThrow(new IllegalStateException("bad json"));
        when(api.searchForced("tt0211915")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitles(3L, "tt0211915", 0, null, NOW)).thenReturn(1);

        startAndRun();

        InOrder order = inOrder(films);
        order.verify(films).findOpenSubtitlesDue(CUTOFF);
        order.verify(films).saveOpenSubtitles(3L, "tt0211915", 0, null, NOW);
        order.verifyNoMoreInteractions();
        assertThat(messages()).contains("Could not search subtitles for film 1: IllegalArgumentException",
            "Could not search subtitles for film 2: IllegalStateException",
            "Subtitle search finished: 1 saved, 0 changed meanwhile, 2 failed");
    }

    @Test
    void aFailureSavingAFilmIsCountedAsFailedAndTheRunCarriesOn() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN, HEAT));
        when(api.searchForced("tt0936501")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(api.searchForced("tt0113277")).thenReturn(new OpenSubtitlesSearch(0, null));
        when(films.saveOpenSubtitles(1L, "tt0936501", 0, null, NOW)).thenThrow(new IllegalStateException("db"));
        when(films.saveOpenSubtitles(2L, "tt0113277", 0, null, NOW)).thenReturn(1);

        startAndRun();

        assertThat(messages()).contains("Subtitle search finished: 1 saved, 0 changed meanwhile, 1 failed");
    }

    @Test
    void aFailureListingTheFilmsIsLoggedAndTheServiceCanRunAgain() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenThrow(new IllegalStateException("database down"));

        startAndRun();

        assertThat(messages()).contains("Subtitle search failed");
        assertThat(refresher.isRunning()).isFalse();
    }

    @Test
    void aSecondStartWhileARunIsGoingIsRefusedAndOnlyOneRunIsQueued() {
        when(api.enabled()).thenReturn(true);

        assertThat(refresher.startDue()).isTrue();
        assertThat(refresher.isRunning()).isTrue();
        assertThat(refresher.startDue()).isFalse();
        refresher.onScanFinished(new ScanFinished("admin"));
        refresher.onPlexImportFinished(new PlexImportFinished());

        assertThat(queued).hasSize(1);
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of());
        queued.get(0).run();
        assertThat(refresher.isRunning()).isFalse();
        assertThat(refresher.startDue()).isTrue();
        assertThat(queued).hasSize(2);
    }

    /** The refresher on an executor that runs the work at once, so a trigger can arrive from inside a run. */
    private OpenSubtitlesRefresher synchronous() {
        return new OpenSubtitlesRefresher(api, films, CLOCK, Runnable::run, events);
    }

    @Test
    void aTriggerDuringARunMakesExactlyOneMoreRun() {
        OpenSubtitlesRefresher inline = synchronous();
        when(api.enabled()).thenReturn(true);
        List<Boolean> started = new ArrayList<>();
        when(films.findOpenSubtitlesDue(CUTOFF)).thenAnswer(call -> {
            if (started.isEmpty()) {
                started.add(inline.startDue());
            }
            return List.of();
        });

        assertThat(inline.startDue()).isTrue();

        assertThat(started).containsExactly(false);
        verify(films, times(2)).findOpenSubtitlesDue(CUTOFF);
        assertThat(inline.isRunning()).isFalse();
    }

    @Test
    void aScanAFreshImportAndASecondTriggerDuringOneRunStillMakeOnlyOneMoreRun() {
        OpenSubtitlesRefresher inline = synchronous();
        when(api.enabled()).thenReturn(true);
        List<Integer> calls = new ArrayList<>();
        when(films.findOpenSubtitlesDue(CUTOFF)).thenAnswer(call -> {
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
        OpenSubtitlesRefresher inline = synchronous();
        when(api.enabled()).thenReturn(true);
        List<Integer> calls = new ArrayList<>();
        when(films.findOpenSubtitlesDue(CUTOFF)).thenAnswer(call -> {
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
    void aRunWithNoTriggerDuringItRunsOnce() {
        OpenSubtitlesRefresher inline = synchronous();
        when(api.enabled()).thenReturn(true);
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of());

        assertThat(inline.startDue()).isTrue();

        verify(films).findOpenSubtitlesDue(CUTOFF);
    }

    @Test
    void aTriggerDuringARunWhileTheSearchIsOffMakesNoRerun() {
        OpenSubtitlesRefresher inline = synchronous();
        when(api.enabled()).thenReturn(true, false);
        List<Boolean> started = new ArrayList<>();
        when(films.findOpenSubtitlesDue(CUTOFF)).thenAnswer(call -> {
            started.add(inline.startDue());
            return List.of();
        });

        assertThat(inline.startDue()).isTrue();

        assertThat(started).containsExactly(false);
        verify(films).findOpenSubtitlesDue(CUTOFF);
    }

    @Test
    void nothingHappensWhenTheKeyIsBlank() {
        when(api.enabled()).thenReturn(false);

        assertThat(refresher.startDue()).isFalse();

        assertThat(queued).isEmpty();
        assertThat(refresher.isRunning()).isFalse();
        verifyNoInteractions(films);
    }

    @Test
    void enabledSaysWhetherTheApiHasAKey() {
        when(api.enabled()).thenReturn(true, false);

        assertThat(refresher.enabled()).isTrue();
        assertThat(refresher.enabled()).isFalse();
    }

    @Test
    void aScanAndAPlexImportBothStartARun() {
        when(api.enabled()).thenReturn(true);

        refresher.onScanFinished(new ScanFinished("nightly"));
        assertThat(queued).hasSize(1);
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of());
        queued.get(0).run();
        refresher.onPlexImportFinished(new PlexImportFinished());

        assertThat(queued).hasSize(2);
    }

    @Test
    void startUpStartsARunWhenTheSearchIsOn() {
        when(api.enabled()).thenReturn(true);

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    @Test
    void startUpSaysOnceThatTheSearchIsOffAndStartsNothing() {
        when(api.enabled()).thenReturn(false);

        refresher.onStartup();

        assertThat(queued).isEmpty();
        assertThat(messages()).containsExactly(
            "Subtitle search is off: no OpenSubtitles API key is configured (OPENSUBTITLES_API_KEY)");
    }

    @Test
    void startUpStaysQuietWhenARunIsAlreadyGoing() {
        when(api.enabled()).thenReturn(true);
        refresher.startDue();

        refresher.onStartup();

        assertThat(queued).hasSize(1);
        assertThat(messages()).isEmpty();
    }

    @Test
    void anExecutorThatRefusesTheRunDoesNotLeaveTheServiceStuckRunning() {
        OpenSubtitlesRefresher refusing = new OpenSubtitlesRefresher(api, films, CLOCK, command -> {
            throw new RejectedExecutionException("full");
        }, events);
        when(api.enabled()).thenReturn(true);

        assertThatThrownBy(refusing::startDue).isInstanceOf(RejectedExecutionException.class);

        assertThat(refusing.isRunning()).isFalse();
    }

    @Test
    void theApplicationReadyEventIsWhatStartUpListensTo() throws NoSuchMethodException {
        var listener = OpenSubtitlesRefresher.class.getMethod("onStartup")
            .getAnnotation(org.springframework.context.event.EventListener.class);

        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void aSearchWithNoResultIsOnlyRepeatedAfterThirtyDays() {
        assertThat(OpenSubtitlesRefresher.RESEARCH_AFTER).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void whenARunEndsTheSearchFinishedEventIsPublishedEvenAfterAnOutage() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of(TAKEN));
        when(api.searchForced("tt0936501")).thenThrow(new OpenSubtitlesUnavailableException("down"));

        startAndRun();

        verify(events).publishEvent(new OpenSubtitlesSearchFinished());
    }

    @Test
    void aListenerThatFailsDoesNotBreakTheRunOrLeaveItStuck() {
        when(films.findOpenSubtitlesDue(CUTOFF)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new IllegalStateException("listener")).when(events)
            .publishEvent(new OpenSubtitlesSearchFinished());

        startAndRun();

        assertThat(messages()).contains("A listener of the subtitle search failed: IllegalStateException");
        assertThat(refresher.isRunning()).isFalse();
    }
}
