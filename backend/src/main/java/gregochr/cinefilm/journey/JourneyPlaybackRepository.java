package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Stores which playback records were watched during which journeys. */
public interface JourneyPlaybackRepository extends JpaRepository<JourneyPlayback, JourneyPlaybackId> {

    /** The links of a journey. */
    List<JourneyPlayback> findByJourneyId(Long journeyId);

    /** The links of a playback record. */
    List<JourneyPlayback> findByPlaybackHealthId(Long playbackHealthId);

    /** The count of links of every journey that has any. */
    @Query("select new gregochr.cinefilm.journey.LinkCount(l.journeyId, count(l)) from JourneyPlayback l "
        + "group by l.journeyId")
    List<LinkCount> countsOfEveryJourney();
}
