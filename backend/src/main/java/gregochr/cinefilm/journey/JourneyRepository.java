package gregochr.cinefilm.journey;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Stores journeys: the stretches of logger runs. */
public interface JourneyRepository extends JpaRepository<Journey, Long> {

    /** Whether any segment of the logger run that started at this time is stored. */
    boolean existsByRunStartedMs(long runStartedMs);

    /** Every journey, newest run first and a run's segments in order. */
    List<Journey> findAllByOrderByRunStartedMsDescSegmentAsc();

    /**
     * One journey by its id, locked for writing until the transaction ends, so that two requests about one journey
     * (giving it a train, taking it away) run one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Journey j where j.id = :id")
    Optional<Journey> findLockedById(Long id);
}
