package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores the modems of a journey. */
public interface JourneyModemRepository extends JpaRepository<JourneyModem, JourneyModemId> {

    /** The modems of a journey, in modem order. */
    List<JourneyModem> findByJourneyIdOrderByModemIndex(Long journeyId);
}
