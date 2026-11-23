package gregochr.cinefilm.journey;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Stores the rounds of a journey. */
public interface JourneySampleRepository extends JpaRepository<JourneySample, JourneySampleId> {

    /** The rounds of a journey, in round order. */
    List<JourneySample> findByJourneyIdOrderByRoundNo(Long journeyId);

    /** The earliest and the latest {@code sampledAt} of a journey; both null when it has no sample. */
    @Query("select new gregochr.cinefilm.journey.SampleSpan(min(s.sampledAt), max(s.sampledAt)) "
        + "from JourneySample s where s.journeyId = :journeyId")
    SampleSpan spanOf(Long journeyId);
}
