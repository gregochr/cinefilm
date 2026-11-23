package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores each modem's state in each round of a journey. */
public interface JourneyModemSampleRepository extends JpaRepository<JourneyModemSample, JourneyModemSampleId> {

    /** The modem states of a journey, in round order and then modem order. */
    List<JourneyModemSample> findByJourneyIdOrderByRoundNoAscModemIndexAsc(Long journeyId);
}
