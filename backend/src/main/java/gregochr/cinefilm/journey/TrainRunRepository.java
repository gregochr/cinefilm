package gregochr.cinefilm.journey;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Stores the train runs. */
public interface TrainRunRepository extends JpaRepository<TrainRun, Long> {

    /** The run of a timetable uid on a date, locked for writing until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TrainRun> findByTimetableUidAndRunDate(String timetableUid, LocalDate runDate);

    /** One run by its id, locked for writing until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from TrainRun r where r.id = :id")
    Optional<TrainRun> findLockedById(Long id);

    /**
     * Deletes a run only if no journey points at it, and says how many rows went (0 or 1). The persistence context is
     * flushed first and cleared afterwards.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TrainRun r where r.id = :id "
        + "and not exists (select 1 from Journey j where j.trainRunId = r.id)")
    int deleteIfUnused(Long id);
}
