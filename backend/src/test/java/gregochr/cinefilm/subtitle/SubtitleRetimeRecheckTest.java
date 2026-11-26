package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.subtitle.SidecarTimingRefresher.Fit;
import gregochr.cinefilm.subtitle.SubtitleRetimeService.TouchedEdition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The re-check after a retime run: an edition is checked only once its film's fit has been made in this run, edition
 * by edition, a busy fit tried again.
 */
@ExtendWith(MockitoExtension.class)
class SubtitleRetimeRecheckTest {

    private static final Set<Long> IDS = Set.of(5L, 6L, 7L);

    @Mock
    private SubtitleRetimeService service;
    @Mock
    private SidecarTimingRefresher timing;
    @Mock
    private SidecarCheckRefresher checks;

    private final List<Duration> pauses = new ArrayList<>();
    private SubtitleRetimeRecheck recheck;

    @BeforeEach
    void setUp() {
        recheck = new SubtitleRetimeRecheck(service, timing, checks, new BusyRetry(pauses::add));
    }

    @Test
    void aFitThatComesBackBusyIsTriedAgainAndTheChecksFollowOnceItIsMade() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.BUSY, Fit.BUSY, Fit.DONE);

        assertThat(recheck.run(IDS)).isTrue();

        verify(timing, times(3)).fitFilm(1L);
        verify(checks).checkEditions(List.of(10L));
        // The full pass is the caller's to start, after its own steps.
        verifyNoMoreInteractions(checks);
        assertThat(pauses).hasSize(2);
    }

    @Test
    void aFitThatStaysBusyGivesUpAfterTheBoundAndTheChecksAreLeftToThePassThatFollowsTheFit() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.BUSY);

        recheck.run(IDS);

        verify(timing, times(BusyRetry.ATTEMPTS)).fitFilm(1L);
        assertThat(pauses).hasSize(BusyRetry.ATTEMPTS - 1);
        // A stale fit would count as "the fit exists": checking now could compare and download before the fit that
        // may prove the file, so the checks wait for the timing pass that is deferred (its end starts them).
        verifyNoInteractions(checks);
    }

    @Test
    void afterTheFirstFilmWhoseFitStaysBusyTheRestAreNotFittedAndTheDeferredPassIsLeftToCheckThem() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L), new TouchedEdition(30L, 3L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.DONE);
        when(timing.fitFilm(2L)).thenReturn(Fit.BUSY);

        recheck.run(IDS);

        verify(checks).checkEditions(List.of(10L));
        verify(timing, times(BusyRetry.ATTEMPTS)).fitFilm(2L);
        verifyNoMoreInteractions(timing, checks);
        // Only the one film's retries were waited for.
        assertThat(pauses).hasSize(BusyRetry.ATTEMPTS - 1);
    }

    @Test
    void aFitLeftToABookedPassIsNotWaitedForAndItsEditionIsNotCheckedYet() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(11L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.LEFT_TO_PASS);

        recheck.run(IDS);

        verify(timing).fitFilm(1L);
        assertThat(pauses).isEmpty();
        verifyNoInteractions(checks);
    }

    @Test
    void aFilmWhoseFitFailedIsNotRetriedOrCheckedAndTheNextEditionStillGoesThroughWithoutAFullPass() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.FAILED);
        when(timing.fitFilm(2L)).thenReturn(Fit.DONE);

        recheck.run(IDS);

        verify(timing).fitFilm(1L);
        assertThat(pauses).isEmpty();
        // Edition 20 is checked; edition 10 waits for the retry of its fit; no full pass is asked for meanwhile (the
        // refresher keeps the failed film for the hourly retry, whose pass announces itself).
        verify(checks).checkEditions(List.of(20L));
        verifyNoMoreInteractions(checks);
    }

    @Test
    void anEditionWhoseChecksThrowDoesNotStopTheNext() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.DONE);
        when(timing.fitFilm(2L)).thenReturn(Fit.DONE);
        when(checks.checkEditions(List.of(10L))).thenThrow(new IllegalStateException("db down"));

        recheck.run(IDS);

        verify(checks).checkEditions(List.of(20L));
    }

    @Test
    void theFullPassIsRequestedFromTheCheckRefresherOnlyWhenTheCallerAsks() {
        recheck.startFullPass();

        verify(checks).startDue();
    }

    @Test
    void aFailureToListTheEditionsIsLoggedNotThrown() {
        when(service.touchedEditions(IDS)).thenThrow(new IllegalStateException("db down"));

        recheck.run(IDS);

        assertThat(pauses).isEmpty();
        verifyNoInteractions(timing, checks);
    }
}
