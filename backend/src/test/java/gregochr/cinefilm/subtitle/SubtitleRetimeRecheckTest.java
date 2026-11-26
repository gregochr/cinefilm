package gregochr.cinefilm.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

/** The re-check after a retime run: the fit first, then the checks, edition by edition, busy fits tried again. */
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
    void eachTouchedEditionIsFittedAndThenCheckedInTurn() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(timing.fitFilm(1L)).thenReturn(true);
        when(timing.fitFilm(2L)).thenReturn(true);

        recheck.run(IDS);

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
        when(timing.fitFilm(1L)).thenReturn(true);

        recheck.run(IDS);

        verify(timing).fitFilm(1L);
        verify(checks).checkEditions(List.of(10L));
        verify(checks).checkEditions(List.of(11L));
    }

    @Test
    void aFitThatComesBackBusyIsTriedAgainAndTheChecksFollowTheFit() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(false, false, true);

        recheck.run(IDS);

        InOrder order = inOrder(timing, checks);
        order.verify(timing, times(3)).fitFilm(1L);
        order.verify(checks).checkEditions(List.of(10L));
        assertThat(pauses).hasSize(2);
    }

    @Test
    void aFitThatStaysBusyGivesUpAfterTheBoundAndTheRowsStayDueForTheNextPass() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L)));
        when(timing.fitFilm(1L)).thenReturn(false);

        recheck.run(IDS);

        verify(timing, times(BusyRetry.ATTEMPTS)).fitFilm(1L);
        assertThat(pauses).hasSize(BusyRetry.ATTEMPTS - 1);
        // The checks still run: a download whose fit is not made yet simply is not due for one.
        verify(checks).checkEditions(List.of(10L));
    }

    @Test
    void anEditionThatFailsDoesNotStopTheNext() {
        when(service.touchedEditions(IDS)).thenReturn(List.of(new TouchedEdition(10L, 1L),
            new TouchedEdition(20L, 2L)));
        when(timing.fitFilm(1L)).thenThrow(new IllegalStateException("db down"));
        when(timing.fitFilm(2L)).thenReturn(true);

        recheck.run(IDS);

        verify(checks).checkEditions(List.of(20L));
    }

    @Test
    void aFailureToListTheEditionsIsLoggedNotThrown() {
        when(service.touchedEditions(IDS)).thenThrow(new IllegalStateException("db down"));

        recheck.run(IDS);

        assertThat(pauses).isEmpty();
    }
}
