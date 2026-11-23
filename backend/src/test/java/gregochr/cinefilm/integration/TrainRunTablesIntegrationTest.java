package gregochr.cinefilm.integration;

import static gregochr.cinefilm.journey.JourneyTestData.call;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.trainRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.journey.Journey;
import gregochr.cinefilm.journey.JourneyRepository;
import gregochr.cinefilm.journey.TrainRun;
import gregochr.cinefilm.journey.TrainRunCall;
import gregochr.cinefilm.journey.TrainRunCallId;
import gregochr.cinefilm.journey.TrainRunCallRepository;
import gregochr.cinefilm.journey.TrainRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The train run tables on PostgreSQL, committed like production: the keys, the plain foreign key, the statements. */
class TrainRunTablesIntegrationTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.of(2030, 1, 15);

    @Autowired
    private TrainRunRepository runs;

    @Autowired
    private TrainRunCallRepository calls;

    @Autowired
    private JourneyRepository journeys;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void data() {
        journeys.deleteAll();
        calls.deleteAll();
        runs.deleteAll();
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }

    private void refused(Runnable work) {
        assertThatThrownBy(() -> inNewTransaction(() -> {
            work.run();
            return null;
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Long storedRun(String uid, LocalDate day) {
        return inNewTransaction(() -> runs.saveAndFlush(trainRun(uid, day)).getId());
    }

    private void storedJourneyOn(Long runId) {
        Journey journey = journey(1_000_000L, 1, null);
        journey.setTrainRunId(runId);
        inNewTransaction(() -> journeys.saveAndFlush(journey));
    }

    @Test
    void theSameUidAndDateIsRefusedAndAnotherDateIsStored() {
        storedRun("X12345", DAY);

        refused(() -> runs.saveAndFlush(trainRun("X12345", DAY)));

        storedRun("X12345", DAY.plusDays(1));
        assertThat(runs.count()).isEqualTo(2);
    }

    @Test
    void aSecondCallWithTheSameKeyIsRefusedAndTheFirstIsUnchanged() {
        Long runId = storedRun("X12345", DAY);
        inNewTransaction(() -> calls.saveAndFlush(call(runId, 1)));
        TrainRunCall again = call(runId, 1);
        again.setLocation("Changed");

        refused(() -> calls.saveAndFlush(again));

        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).singleElement()
            .satisfies(row -> assertThat(row.getLocation()).isEqualTo("Alpha Junction"));
    }

    @Test
    void aCallWithoutItsRunIsRefused() {
        refused(() -> calls.saveAndFlush(call(999_999L, 1)));

        assertThat(calls.count()).isZero();
    }

    @Test
    void aRunThatAJourneyPointsAtCannotBeDeletedByThePlainDelete() {
        Long runId = storedRun("X12345", DAY);
        storedJourneyOn(runId);

        refused(() -> {
            runs.deleteById(runId);
            runs.flush();
        });

        assertThat(runs.findById(runId)).isPresent();
    }

    @Test
    void theSweepDeletesARunNoJourneyPointsAtAndTakesItsCalls() {
        Long runId = storedRun("X12345", DAY);
        inNewTransaction(() -> calls.saveAndFlush(call(runId, 1)));

        int deleted = inNewTransaction(() -> runs.deleteIfUnused(runId));

        assertThat(deleted).isEqualTo(1);
        assertThat(runs.findById(runId)).isEmpty();
        assertThat(calls.count()).isZero();
    }

    @Test
    void theSweepLeavesARunThatAJourneyPointsAtWithItsCalls() {
        Long runId = storedRun("X12345", DAY);
        inNewTransaction(() -> calls.saveAndFlush(call(runId, 1)));
        storedJourneyOn(runId);

        int deleted = inNewTransaction(() -> runs.deleteIfUnused(runId));

        assertThat(deleted).isZero();
        assertThat(runs.findById(runId)).isPresent();
        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).hasSize(1);
    }

    @Test
    void theBulkDeleteThenNewCallsWithTheSameSeqsAreStoredInOneTransaction() {
        Long runId = storedRun("X12345", DAY);
        Long otherRunId = storedRun("X12345", DAY.plusDays(1));
        inNewTransaction(() -> calls.saveAllAndFlush(List.of(call(runId, 1), call(runId, 2), call(otherRunId, 1))));

        inNewTransaction(() -> {
            calls.deleteAllOfRun(runId);
            TrainRunCall again = call(runId, 1);
            again.setLocation("Beta Parkway");
            return calls.saveAndFlush(again);
        });

        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).extracting(TrainRunCall::getId)
            .containsExactly(new TrainRunCallId(runId, 1));
        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).singleElement()
            .satisfies(row -> assertThat(row.getLocation()).isEqualTo("Beta Parkway"));
        assertThat(calls.findByTrainRunIdOrderBySeq(otherRunId)).hasSize(1);
    }

    @Test
    void theLockedFinderFindsAStoredRunInsideATransactionAndGivesEmptyForAnUnknownOne() {
        Long runId = storedRun("X12345", DAY);

        List<Object> found = inNewTransaction(() -> {
            TrainRun run = runs.findByTimetableUidAndRunDate("X12345", DAY).orElseThrow();
            return List.of(run.getId(), entityManager.getLockMode(run));
        });
        boolean unknown = inNewTransaction(() -> runs.findByTimetableUidAndRunDate("Y99999", DAY).isPresent());

        assertThat(found).containsExactly(runId, LockModeType.PESSIMISTIC_WRITE);
        assertThat(unknown).isFalse();
    }
}
