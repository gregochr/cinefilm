package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the link map from every stored journey, on each request: nothing is cached, so a deleted journey is gone
 * from the next answer. The player keeps the answer in memory only (SECURITY.md rule 7).
 */
@Service
public class LinkMapService {

    private final JourneyRepository journeys;
    private final TrackPointService trackPoints;

    /** Creates the service over the journeys and the track points of each. */
    public LinkMapService(JourneyRepository journeys, TrackPointService trackPoints) {
        this.journeys = journeys;
        this.trackPoints = trackPoints;
    }

    /**
     * The map of every journey's track, the newest run first, leaving out the journey with the id
     * {@code excludeJourney} (null leaves none out; an id that is not stored leaves none out either).
     */
    public LinkMap linkMap(Long excludeJourney) {
        List<List<TrackPoint>> tracks = journeys.findAllByOrderByRunStartedMsDescSegmentAsc().stream()
            .filter(journey -> !journey.getId().equals(excludeJourney))
            .map(journey -> trackPoints.pointsOf(journey.getId()))
            .toList();
        return new LinkMap(LinkStretches.build(tracks));
    }
}
