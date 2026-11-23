package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores the rounds of a journey. */
public interface JourneySampleRepository extends JpaRepository<JourneySample, JourneySampleId> {

    /** The rounds of a journey, in round order. */
    List<JourneySample> findByJourneyIdOrderByRoundNo(Long journeyId);
}
