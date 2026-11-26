package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import gregochr.cinefilm.transcode.KeyframeIndexPassFinished;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/** The retime job: when it runs, what it asks the service for, what it announces and what it re-checks. */
@ExtendWith(MockitoExtension.class)
class SubtitleRetimeJobTest {

    @Mock
    private SubtitleRetimeService service;
    @Mock
    private ReferenceRetimeService referenceService;
    @Mock
    private SubtitleRetimeRecheck recheck;
    @Mock
    private ApplicationEventPublisher events;

    private final List<Runnable> queued = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final Executor executor = queued::add;
    private final Logger jobLog = (Logger) LoggerFactory.getLogger(SubtitleRetimeJob.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void attachLog() {
        logged.start();
        jobLog.addAppender(logged);
    }

    @AfterEach
    void detachLog() {
        jobLog.detachAppender(logged);
    }

    private List<String> messages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private SubtitleRetimeJob job(String setting) {
        return job(setting, executor);
    }

    private SubtitleRetimeJob job(String setting, Executor on) {
        return new SubtitleRetimeJob(service, referenceService, recheck, new BusyRetry(pauses::add), on, setting,
            events);
    }

    @Test
    void withTheSettingOffNothingIsStartedAndNothingIsAsked() {
        SubtitleRetimeJob off = job("off");

        assertThat(off.start()).isFalse();
        off.onStartup();
        off.onIndexPassFinished(new KeyframeIndexPassFinished());

        assertThat(queued).isEmpty();
        verifyNoInteractions(service, referenceService, recheck, events);
    }

    @Test
    void aTimingCheckThatFittedSomethingStartsARunAndDoesNothingWhileOff() {
        job("on").onTimingFinished(new SidecarTimingFinished());
        assertThat(queued).hasSize(1);

        queued.clear();
        job("off").onTimingFinished(new SidecarTimingFinished());
        assertThat(queued).isEmpty();
    }

    @Test
    void anUnknownSettingIsOff() {
        assertThat(job("sometimes").start()).isFalse();
        assertThat(job(null).start()).isFalse();
        assertThat(queued).isEmpty();
    }

    @Test
    void startingQueuesOneRunAtATimeAndAFinishedRunAllowsTheNext() {
        when(service.candidateIds()).thenReturn(List.of());
        SubtitleRetimeJob on = job("on");

        assertThat(on.start()).isTrue();
        assertThat(on.start()).isFalse();
        queued.remove(0).run();
        assertThat(on.start()).isTrue();
    }

    @Test
    void startupAndAFinishedIndexPassEachStartARun() {
        SubtitleRetimeJob restore = job("restore");

        restore.onStartup();
        assertThat(queued).hasSize(1);
        when(service.candidateIds()).thenReturn(List.of());
        queued.remove(0).run();
        restore.onIndexPassFinished(new KeyframeIndexPassFinished());

        assertThat(queued).hasSize(1);
    }

    @Test
    void aRequestDuringARunMakesExactlyOneMoreRunHoweverManyArrive() {
        SubtitleRetimeJob on = job("on");
        List<Boolean> asked = new ArrayList<>();
        when(service.candidateIds()).thenAnswer(call -> {
            if (asked.isEmpty()) {
                // The index pass ends, and the nightly run asks, while this run is going.
                asked.add(on.start());
                on.onIndexPassFinished(new KeyframeIndexPassFinished());
            }
            return List.of();
        });
        on.start();

        queued.get(0).run();

        assertThat(asked).containsExactly(false);
        verify(service, times(2)).candidateIds();
        assertThat(queued).hasSize(1);
    }

    @Test
    void aRequestThatLandsAfterTheLastCheckButBeforeTheRunIsGivenUpStartsANewRun() {
        when(service.candidateIds()).thenReturn(List.of());
        boolean[] asked = {false};
        SubtitleRetimeJob on = new SubtitleRetimeJob(service, referenceService, recheck,
            new BusyRetry(pauses::add), executor, "on", events) {
            @Override
            void afterLastCheck() {
                if (!asked[0]) {
                    asked[0] = true;
                    // The run is still held, so this cannot start one: only the relaunch after it is given up can.
                    assertThat(start()).isFalse();
                }
            }
        };
        on.start();

        queued.remove(0).run();

        assertThat(queued).hasSize(1);
    }

    @Test
    void aRefusedRunFreesTheJobAndTheErrorReachesTheCaller() {
        Executor refusing = task -> {
            throw new IllegalStateException("full");
        };
        SubtitleRetimeJob on = job("on", refusing);

        assertThatThrownBy(on::start).isInstanceOf(IllegalStateException.class).hasMessage("full");
        assertThatThrownBy(on::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRunVisitsEveryCandidateInTheSettingsModeAnnouncesTheChangedOnesAndThenRechecksThem() {
        when(service.candidateIds()).thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);
        when(service.process(2L, RetimeMode.ON)).thenReturn(Outcome.RESTORED);
        when(service.process(3L, RetimeMode.ON)).thenReturn(Outcome.DECIDED_OFF);
        when(service.process(4L, RetimeMode.ON)).thenReturn(Outcome.DECIDED_FITS);
        when(service.process(5L, RetimeMode.ON)).thenReturn(Outcome.UNCHANGED);
        when(service.process(6L, RetimeMode.ON)).thenThrow(new IllegalStateException("boom"));

        job("on").run();

        // The event is published after the re-check: its listeners book passes that would hold the re-check's fits.
        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(1L, 2L)));
        InOrder order = inOrder(events, recheck);
        order.verify(recheck).run(Set.of(1L, 2L), Set.of());
        order.verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(1L, 2L)));
        verifyNoMoreInteractions(recheck);
        assertThat(messages()).containsExactly("Could not retime download 6: IllegalStateException",
            "Subtitle retime (ON) finished: 1 rewritten, 1 restored, 1 set off, 1 fit as they are, 1 left as "
                + "they were, 0 left for the next run (library busy), 1 failed; references: 0 rewritten, 0 restored");
    }

    @Test
    void theFinishLineThatSaysTheRunEndedIsLoggedAfterTheRecheck() {
        when(service.candidateIds()).thenReturn(List.of(1L));
        when(service.process(1L, RetimeMode.RESTORE)).thenReturn(Outcome.RESTORED);
        List<List<String>> seenByRecheck = new ArrayList<>();
        doAnswer(call -> seenByRecheck.add(messages())).when(recheck).run(Set.of(1L), Set.of());

        job("restore").run();

        assertThat(seenByRecheck).containsExactly(List.of());
        assertThat(messages()).hasSize(1);
        assertThat(messages().get(0)).startsWith("Subtitle retime (RESTORE) finished: 0 rewritten, 1 restored");
    }

    @Test
    void theFitsAreAnnouncedAfterTheEventWhenTheReCheckSavedAny() {
        when(service.candidateIds()).thenReturn(List.of(1L));
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);
        when(recheck.run(Set.of(1L), Set.of())).thenReturn(true);

        job("on").run();

        InOrder order = inOrder(events, recheck);
        order.verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(1L)));
        order.verify(recheck).announceFits();
    }

    @Test
    void nothingIsAnnouncedWhenTheReCheckSavedNoFit() {
        when(service.candidateIds()).thenReturn(List.of(1L));
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);
        when(recheck.run(Set.of(1L), Set.of())).thenReturn(false);

        job("on").run();

        verify(recheck).run(Set.of(1L), Set.of());
        verifyNoMoreInteractions(recheck);
    }

    @Test
    void aRunThatChangedNothingAnnouncesAndRechecksNothing() {
        when(service.candidateIds()).thenReturn(List.of(1L));
        when(service.process(1L, RetimeMode.RESTORE)).thenReturn(Outcome.UNCHANGED);

        job("restore").run();

        verifyNoInteractions(events, recheck);
    }

    @Test
    void aBusyLibraryIsTriedAgainAndTheRowIsThenRewritten() {
        when(service.candidateIds()).thenReturn(List.of(1L));
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.BUSY, Outcome.BUSY, Outcome.REWRITTEN);

        job("on").run();

        verify(service, times(3)).process(1L, RetimeMode.ON);
        assertThat(pauses).hasSize(2);
        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(1L)));
    }

    @Test
    void aLibraryThatStaysBusyGivesUpAfterTheBoundLeavesTheRowDueAndStopsThePass() {
        when(service.candidateIds()).thenReturn(List.of(1L, 2L));
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.BUSY);

        job("on").run();

        verify(service).candidateIds();
        verify(service, times(BusyRetry.ATTEMPTS)).process(1L, RetimeMode.ON);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(events, recheck);
        assertThat(messages()).containsExactly("Subtitle retime stops: the library is busy",
            "Subtitle retime (ON) finished: 0 rewritten, 0 restored, 0 set off, 0 fit as they are, 0 left as they "
                + "were, 1 left for the next run (library busy), 0 failed; references: 0 rewritten, 0 restored");
    }

    @Test
    void aFailureOfTheWholeRunIsLoggedAndNotThrown() {
        when(service.candidateIds()).thenThrow(new IllegalStateException("db down"));

        job("on").run();

        verifyNoInteractions(events, recheck);
    }

    @Test
    void referencesAreVisitedBeforeDownloadsAndTheFilmsOfChangedOnesAreRechecked() {
        List<String> order = new ArrayList<>();
        when(referenceService.candidateIds()).thenAnswer(call -> {
            order.add("references");
            return List.of(9L, 10L);
        });
        when(referenceService.process(9L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);
        when(referenceService.process(10L, RetimeMode.ON)).thenReturn(Outcome.RESTORED);
        when(service.candidateIds()).thenAnswer(call -> {
            order.add("downloads");
            return List.of(1L);
        });
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);

        job("on").run();

        assertThat(order).containsExactly("references", "downloads");
        verify(recheck).run(Set.of(1L), Set.of(9L, 10L));
        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(1L)));
        assertThat(messages()).containsExactly(
            "Subtitle retime (ON) finished: 1 rewritten, 0 restored, 0 set off, 0 fit as they are, 0 left as "
                + "they were, 0 left for the next run (library busy), 0 failed; references: 1 rewritten, "
                + "1 restored");
    }

    @Test
    void aRunThatChangedOnlyReferencesStillRechecksTheirFilmsAndAnnouncesWithNoDownloads() {
        when(referenceService.candidateIds()).thenReturn(List.of(9L));
        when(referenceService.process(9L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);
        when(recheck.run(Set.of(), Set.of(9L))).thenReturn(true);

        job("on").run();

        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of()));
        verify(recheck).announceFits();
    }

    @Test
    void aLibraryThatStaysBusyDuringTheReferencesStopsTheRunBeforeAnyDownloadIsLookedAt() {
        when(referenceService.candidateIds()).thenReturn(List.of(9L, 10L));
        when(referenceService.process(9L, RetimeMode.ON)).thenReturn(Outcome.BUSY);

        job("on").run();

        verify(referenceService, times(BusyRetry.ATTEMPTS)).process(9L, RetimeMode.ON);
        verifyNoInteractions(service, events, recheck);
        assertThat(messages()).containsExactly("Subtitle retime stops: the library is busy",
            "Subtitle retime (ON) finished: 0 rewritten, 0 restored, 0 set off, 0 fit as they are, 0 left as they "
                + "were, 0 left for the next run (library busy), 0 failed; references: 0 rewritten, 0 restored");
    }

    @Test
    void aReferenceThatThrowsIsCountedAndPassedOverAndTheDownloadsStillRun() {
        when(referenceService.candidateIds()).thenReturn(List.of(9L));
        when(referenceService.process(9L, RetimeMode.ON)).thenThrow(new IllegalStateException("boom"));
        when(service.candidateIds()).thenReturn(List.of());

        job("on").run();

        assertThat(messages()).containsExactly("Could not retime reference 9: IllegalStateException",
            "Subtitle retime (ON) finished: 0 rewritten, 0 restored, 0 set off, 0 fit as they are, 0 left as they "
                + "were, 0 left for the next run (library busy), 1 failed; references: 0 rewritten, 0 restored");
    }
}
