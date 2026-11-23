package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyTestData.call;
import static gregochr.cinefilm.journey.JourneyTestData.fullCall;
import static gregochr.cinefilm.journey.JourneyTestData.fullTrainRun;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.trainRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The train runs and their calls: round-trips, the keys, the order, the cascade and the two statements. */
class TrainRunRepositoryTest extends ApiTestBase {

    private static final LocalDate DAY = LocalDate.of(2030, 1, 15);

    @Autowired
    private TrainRunRepository runs;

    @Autowired
    private TrainRunCallRepository calls;

    @Autowired
    private JourneyRepository journeys;

    @Autowired
    private EntityManager entityManager;

    private Long newRun() {
        return runs.saveAndFlush(trainRun("X12345", DAY)).getId();
    }

    @Test
    void aRunWithEveryColumnFilledReadsBackWithEveryColumn() {
        Long id = runs.saveAndFlush(fullTrainRun()).getId();
        entityManager.clear();

        TrainRun loaded = runs.findById(id).orElseThrow();

        TrainRun expected = fullTrainRun();
        expected.setId(id);
        assertThat(expected).hasNoNullFieldsOrProperties();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void aCallWithEveryColumnFilledReadsBackWithEveryColumn() {
        Long runId = newRun();
        calls.saveAndFlush(fullCall(runId));
        entityManager.clear();

        TrainRunCall loaded = calls.findById(new TrainRunCallId(runId, 4)).orElseThrow();

        TrainRunCall expected = fullCall(runId);
        assertThat(expected).hasNoNullFieldsOrProperties();
        assertThat(loaded).usingRecursiveComparison().ignoringFields("stored").isEqualTo(expected);
    }

    @Test
    void theSameUidAndDateCannotBeStoredTwiceButTheSameUidOnAnotherDateCan() {
        newRun();

        assertThatThrownBy(() -> runs.saveAndFlush(trainRun("X12345", DAY)))
            .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        runs.saveAndFlush(trainRun("X12345", DAY.plusDays(1)));
        assertThat(runs.count()).isEqualTo(2);
    }

    @Test
    void theSameSeqOfARunCannotBeStoredTwiceAndTheFirstIsUnchanged() {
        Long runId = newRun();
        TrainRunCall first = call(runId, 1);
        first.setLocation("Alpha Junction");
        calls.saveAndFlush(first);
        entityManager.clear();
        TrainRunCall again = call(runId, 1);
        again.setLocation("Changed");

        assertThatThrownBy(() -> calls.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        assertThat(calls.findById(new TrainRunCallId(runId, 1)).orElseThrow().getLocation())
            .isEqualTo("Alpha Junction");
        assertThat(calls.count()).isEqualTo(1);
    }

    @Test
    void aSavedCallIsNotNewSoSavingTheSameInstanceAgainIsAnUpdate() {
        Long runId = newRun();
        TrainRunCall call = call(runId, 1);
        assertThat(call.isNew()).isTrue();
        calls.saveAndFlush(call);
        assertThat(call.isNew()).isFalse();
        assertThat(call.getId()).isEqualTo(new TrainRunCallId(runId, 1));
        entityManager.clear();

        call.setLocation("Beta Parkway");
        calls.saveAndFlush(call);
        entityManager.clear();

        TrainRunCall loaded = calls.findById(call.getId()).orElseThrow();
        assertThat(loaded.getLocation()).isEqualTo("Beta Parkway");
        assertThat(loaded.isNew()).isFalse();
        assertThat(calls.count()).isEqualTo(1);
    }

    @Test
    void callsComeBackInSeqOrder() {
        Long runId = newRun();
        Long otherRunId = runs.saveAndFlush(trainRun("X12345", DAY.plusDays(1))).getId();
        calls.saveAndFlush(call(runId, 3));
        calls.saveAndFlush(call(runId, 1));
        calls.saveAndFlush(call(otherRunId, 2));
        calls.saveAndFlush(call(runId, 2));
        entityManager.clear();

        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).extracting(TrainRunCall::getSeq)
            .containsExactly(1, 2, 3);
    }

    @Test
    void aCallWithoutItsRunIsRefused() {
        assertThatThrownBy(() -> calls.saveAndFlush(call(999_999L, 1)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingARunTakesItsCalls() {
        Long runId = newRun();
        calls.saveAndFlush(call(runId, 1));
        calls.saveAndFlush(call(runId, 2));
        entityManager.clear();

        runs.deleteById(runId);
        runs.flush();
        entityManager.clear();

        assertThat(runs.count()).isZero();
        assertThat(calls.count()).isZero();
    }

    @Test
    void aJourneyPointsAtARunAndTwoJourneysMayPointAtOne() {
        Long runId = newRun();
        Journey first = journey(1_000_000L, 1, null);
        first.setTrainRunId(runId);
        Journey second = journey(1_000_000L, 2, null);
        second.setTrainRunId(runId);
        Long firstId = journeys.saveAndFlush(first).getId();
        Long secondId = journeys.saveAndFlush(second).getId();
        entityManager.clear();

        assertThat(journeys.findById(firstId).orElseThrow().getTrainRunId()).isEqualTo(runId);
        assertThat(journeys.findById(secondId).orElseThrow().getTrainRunId()).isEqualTo(runId);
    }

    @Test
    void aRunThatAJourneyPointsAtCannotBeDeletedByThePlainDelete() {
        Long runId = newRun();
        Journey journey = journey(1_000_000L, 1, null);
        journey.setTrainRunId(runId);
        journeys.saveAndFlush(journey);
        entityManager.clear();

        assertThatThrownBy(() -> {
            runs.deleteById(runId);
            runs.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSweepDeletesARunNoJourneyPointsAtAndTakesItsCalls() {
        Long runId = newRun();
        calls.saveAndFlush(call(runId, 1));

        int deleted = runs.deleteIfUnused(runId);

        assertThat(deleted).isEqualTo(1);
        assertThat(runs.findById(runId)).isEmpty();
        assertThat(calls.count()).isZero();
    }

    @Test
    void theSweepLeavesARunThatAJourneyPointsAtWithItsCalls() {
        Long runId = newRun();
        calls.saveAndFlush(call(runId, 1));
        Journey journey = journey(1_000_000L, 1, null);
        journey.setTrainRunId(runId);
        journeys.saveAndFlush(journey);

        int deleted = runs.deleteIfUnused(runId);

        assertThat(deleted).isZero();
        assertThat(runs.findById(runId)).isPresent();
        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).hasSize(1);
    }

    @Test
    void theBulkDeleteRemovesAllOfOneRunsCallsAndNoneOfAnothersAndTheSeqsCanBeStoredAgain() {
        Long runId = newRun();
        Long otherRunId = runs.saveAndFlush(trainRun("X12345", DAY.plusDays(1))).getId();
        calls.saveAllAndFlush(List.of(call(runId, 1), call(runId, 2), call(otherRunId, 1)));

        int deleted = calls.deleteAllOfRun(runId);

        assertThat(deleted).isEqualTo(2);
        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).isEmpty();
        assertThat(calls.findByTrainRunIdOrderBySeq(otherRunId)).hasSize(1);
        TrainRunCall again = call(runId, 1);
        again.setLocation("Beta Parkway");
        calls.saveAndFlush(again);
        entityManager.clear();
        assertThat(calls.findById(new TrainRunCallId(runId, 1)).orElseThrow().getLocation())
            .isEqualTo("Beta Parkway");
    }

    @Test
    void theLockedFinderByIdFindsAStoredRunHoldsTheLockAndGivesEmptyForAnUnknownOne() {
        Long runId = newRun();
        entityManager.clear();

        TrainRun found = runs.findLockedById(runId).orElseThrow();

        assertThat(found.getTimetableUid()).isEqualTo("X12345");
        assertThat(entityManager.getLockMode(found)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(runs.findLockedById(runId + 1)).isEmpty();
    }

    @Test
    void theLockedFinderFindsAStoredRunAndGivesEmptyForAnUnknownOne() {
        Long runId = newRun();
        entityManager.clear();

        TrainRun found = runs.findByTimetableUidAndRunDate("X12345", DAY).orElseThrow();

        assertThat(found.getId()).isEqualTo(runId);
        assertThat(entityManager.getLockMode(found)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(runs.findByTimetableUidAndRunDate("X12345", DAY.plusDays(1))).isEmpty();
        assertThat(runs.findByTimetableUidAndRunDate("Y99999", DAY)).isEmpty();
    }
}
