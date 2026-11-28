package gregochr.cinefilm.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Every coordinate is made up, in the middle of an ocean. */
@ExtendWith(MockitoExtension.class)
class LinkMapServiceTest {

    @Mock
    private JourneyRepository journeys;
    @Mock
    private TrackPointService trackPoints;

    private LinkMapService service;

    @BeforeEach
    void setUp() {
        service = new LinkMapService(journeys, trackPoints);
    }

    private static Journey journey(long id) {
        Journey journey = new Journey();
        journey.setId(id);
        return journey;
    }

    private static List<TrackPoint> track(double latitude) {
        return List.of(new TrackPoint(latitude, -20.0, 0.0, 30.0, 0, PointState.GOOD),
            new TrackPoint(latitude + 0.01, -20.0, 0.0, 30.0, 20, PointState.GOOD));
    }

    @Test
    void everyJourneyBecomesATrackInTheRepositorysOrder() {
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc()).thenReturn(List.of(journey(3), journey(1)));
        when(trackPoints.pointsOf(3L)).thenReturn(track(0.5));
        when(trackPoints.pointsOf(1L)).thenReturn(track(0.6));

        LinkMap map = service.linkMap(null);

        assertThat(map.tracks()).hasSize(2);
        assertThat(map.tracks().get(0).points().get(0).latitude()).isEqualTo(0.5);
        assertThat(map.tracks().get(1).points().get(0).latitude()).isEqualTo(0.6);
    }

    @Test
    void theExcludedJourneyIsNeverReadAndLeavesNoTrack() {
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc()).thenReturn(List.of(journey(3), journey(1)));
        when(trackPoints.pointsOf(1L)).thenReturn(track(0.6));

        LinkMap map = service.linkMap(3L);

        assertThat(map.tracks()).hasSize(1);
        assertThat(map.tracks().get(0).points().get(0).latitude()).isEqualTo(0.6);
        verify(trackPoints).pointsOf(1L);
        verifyNoMoreInteractions(trackPoints);
    }

    @Test
    void anExcludedIdThatIsNotStoredLeavesEveryTrackIn() {
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc()).thenReturn(List.of(journey(1)));
        when(trackPoints.pointsOf(1L)).thenReturn(track(0.6));

        assertThat(service.linkMap(99L).tracks()).hasSize(1);
    }

    @Test
    void noJourneysIsAnEmptyMapAndAJourneyWithNoPositionIsLeftOut() {
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc()).thenReturn(List.of(journey(1)));
        when(trackPoints.pointsOf(1L)).thenReturn(List.of());

        assertThat(service.linkMap(null).tracks()).isEmpty();
    }
}
