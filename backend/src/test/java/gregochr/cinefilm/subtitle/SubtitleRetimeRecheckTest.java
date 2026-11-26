package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.InOrder;
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
    void eachTouchedEditionIsCheckedAfterItsFilmWasFittedInTurn() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.DONE);
        when(timing.fitFilm(2L)).thenReturn(Fit.DONE);

        recheck.run(IDS);

        verify(checks).checkEditions(List.of(10L));
        verify(checks).checkEditions(List.of(20L));
        // The order is the point of the re-check, besides the calls themselves.
        InOrder order = inOrder(timing, checks);
        order.verify(timing).fitFilm(1L);
        order.verify(checks).checkEditions(List.of(10L));
        order.verify(timing).fitFilm(2L);
        order.verify(checks).checkEditions(List.of(20L));
        assertThat(pauses).isEmpty();
    }

    @Test
    void aFilmWithTwoTouchedEditionsIsFittedOnceAndBothAreChecked() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(11L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.DONE);

        recheck.run(IDS);

        verify(timing).fitFilm(1L);
        verify(checks).checkEditions(List.of(10L));
        verify(checks).checkEditions(List.of(11L));
    }

    @Test
    void aFitThatComesBackBusyIsTriedAgainAndTheChecksFollowOnceItIsMade() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.BUSY, Fit.BUSY, Fit.DONE);

        recheck.run(IDS);

        verify(timing, times(3)).fitFilm(1L);
        verify(checks).checkEditions(List.of(10L));
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
    void aFitThatFailedIsNotTriedAgainAndItsEditionIsNotChecked() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(Fit.FAILED);

        recheck.run(IDS);

        verify(timing).fitFilm(1L);
        assertThat(pauses).isEmpty();
        verifyNoInteractions(checks);
    }

    @Test
    void anEditionThatFailsDoesNotStopTheNext() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(timing.fitFilm(1L)).thenThrow(new IllegalStateException("db down"));
        when(timing.fitFilm(2L)).thenReturn(Fit.DONE);

        recheck.run(IDS);

        verify(checks).checkEditions(List.of(20L));
    }

    @Test
    void aFailureToListTheEditionsIsLoggedNotThrown() {
        when(service.touchedEditions(IDS)).thenThrow(new IllegalStateException("db down"));

        recheck.run(IDS);

        assertThat(pauses).isEmpty();
        verifyNoInteractions(timing, checks);
    }
}
