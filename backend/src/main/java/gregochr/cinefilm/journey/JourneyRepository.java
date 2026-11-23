package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores journeys: the stretches of logger runs. */
public interface JourneyRepository extends JpaRepository<Journey, Long> {

    /** Whether any segment of the logger run that started at this time is stored. */
    boolean existsByRunStartedMs(long runStartedMs);

    /** Every journey, newest run first and a run's segments in order. */
    List<Journey> findAllByOrderByRunStartedMsDescSegmentAsc();
}
