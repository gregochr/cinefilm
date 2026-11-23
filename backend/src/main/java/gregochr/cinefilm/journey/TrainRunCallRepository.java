package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Stores the timing points of the train runs. */
public interface TrainRunCallRepository extends JpaRepository<TrainRunCall, TrainRunCallId> {

    /** The calls of a run, in order. */
    List<TrainRunCall> findByTrainRunIdOrderBySeq(Long trainRunId);

    /** Deletes every call of a run; the persistence context is flushed first and cleared afterwards. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TrainRunCall c where c.trainRunId = :trainRunId")
    int deleteAllOfRun(Long trainRunId);
}
