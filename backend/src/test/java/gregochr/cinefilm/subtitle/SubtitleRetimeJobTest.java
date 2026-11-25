package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.subtitle.SubtitleRetimeService.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** The retime job: when it runs, what it asks the service for and what it announces. */
@ExtendWith(MockitoExtension.class)
class SubtitleRetimeJobTest {

    @Mock
    private SubtitleRetimeService service;
    @Mock
    private ApplicationEventPublisher events;

    private final List<Runnable> queued = new ArrayList<>();
    private final Executor executor = queued::add;

    private SubtitleRetimeJob job(String setting) {
        return new SubtitleRetimeJob(service, executor, setting, events);
    }

    @Test
    void withTheSettingOffNothingIsStartedAndTheServiceIsNeverAsked() {
        SubtitleRetimeJob off = job("off");

        assertThat(off.start()).isFalse();
        off.onStartup();

        assertThat(queued).isEmpty();
        verifyNoInteractions(service, events);
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
    void startupStartsARun() {
        job("restore").onStartup();

        assertThat(queued).hasSize(1);
    }

    @Test
    void aRefusedRunFreesTheJobAndTheErrorReachesTheCaller() {
        SubtitleRetimeJob on = new SubtitleRetimeJob(service, task -> {
            throw new IllegalStateException("full");
        }, "on", events);

        assertThatThrownBy(on::start).isInstanceOf(IllegalStateException.class).hasMessage("full");
        assertThatThrownBy(on::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRunVisitsEveryCandidateInTheSettingsModeAndAnnouncesTheRewrittenAndRestoredOnes() {
        when(service.candidateIds()).thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L));
        when(service.process(1L, RetimeMode.ON)).thenReturn(Outcome.REWRITTEN);
        when(service.process(2L, RetimeMode.ON)).thenReturn(Outcome.RESTORED);
        when(service.process(3L, RetimeMode.ON)).thenReturn(Outcome.DECIDED_OFF);
        when(service.process(4L, RetimeMode.ON)).thenReturn(Outcome.UNCHANGED);
        when(service.process(5L, RetimeMode.ON)).thenReturn(Outcome.BUSY);
        when(service.process(6L, RetimeMode.ON)).thenThrow(new IllegalStateException("boom"));

        job("on").run();

        verify(events).publishEvent(new SubtitleCuesRewritten(Set.of(1L, 2L)));
    }

    @Test
    void aRunThatChangedNothingAnnouncesNothing() {
        when(service.candidateIds()).thenReturn(List.of(1L));
        when(service.process(1L, RetimeMode.RESTORE)).thenReturn(Outcome.UNCHANGED);

        job("restore").run();

        verifyNoInteractions(events);
    }

    @Test
    void aFailureOfTheWholeRunIsLoggedAndNotThrown() {
        when(service.candidateIds()).thenThrow(new IllegalStateException("db down"));

        job("on").run();

        verifyNoInteractions(events);
    }
}
