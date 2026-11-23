package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores which playback records were watched during which journeys. */
public interface JourneyPlaybackRepository extends JpaRepository<JourneyPlayback, JourneyPlaybackId> {

    /** The links of a journey. */
    List<JourneyPlayback> findByJourneyId(Long journeyId);

    /** The links of a playback record. */
    List<JourneyPlayback> findByPlaybackHealthId(Long playbackHealthId);
}
