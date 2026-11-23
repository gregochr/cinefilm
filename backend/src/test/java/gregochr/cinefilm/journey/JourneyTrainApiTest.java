package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.sample;
import static gregochr.cinefilm.journey.TrainFileSamples.example;
import static gregochr.cinefilm.journey.TrainFileSamples.firstCall;
import static gregochr.cinefilm.journey.TrainFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.ResultActions;
import gregochr.cinefilm.ApiTestBase;
import tools.jackson.databind.node.ObjectNode;

/** Giving a journey its train and taking it away, end to end on H2, read back through the repositories. */
class JourneyTrainApiTest extends ApiTestBase {

    private static final LocalDate DAY = LocalDate.of(2030, 3, 4);

    @Autowired
    private JourneyRepository journeys;
    @Autowired
    private JourneySampleRepository samples;
    @Autowired
    private TrainRunRepository runs;
    @Autowired
    private TrainRunCallRepository calls;
    @Autowired
    private EntityManager entityManager;

    private Long first;
    private Long second;

    @BeforeEach
    void journeysWithSamples() {
        first = journeyWithSamples(1_000L);
        second = journeyWithSamples(2_000L);
    }

    private Long journeyWithSamples(long runStartedMs) {
        Long id = journeys.saveAndFlush(journey(runStartedMs, 1, null)).getId();
        JourneySample early = sample(id, 1);
        early.setSampledAt(Instant.parse("2030-03-04T22:10:00Z"));
        JourneySample late = sample(id, 2);
        late.setSampledAt(Instant.parse("2030-03-04T23:10:00Z"));
        samples.saveAllAndFlush(List.of(early, late));
        return id;
    }

    private ResultActions give(Long journeyId, ObjectNode file) throws Exception {
        return mvc.perform(put("/api/admin/journeys/{id}/train", journeyId).content(text(file)));
    }

    private ResultActions takeAway(Long journeyId) throws Exception {
        return mvc.perform(authed(MockMvcRequestBuilders.delete("/api/admin/journeys/{id}/train", journeyId)));
    }

    private void readBack() {
        entityManager.flush();
        entityManager.clear();
    }

    private Long trainOf(Long journeyId) {
        return journeys.findById(journeyId).orElseThrow().getTrainRunId();
    }

    @Test
    void theExampleAnswers200AndTheRunItsCallsAndTheJourneysTrainReadBack() throws Exception {
        give(first, example())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uid").value("X12345"))
            .andExpect(jsonPath("$.date").value("2030-03-04"))
            .andExpect(jsonPath("$.headcode").value("9Z99"))
            .andExpect(jsonPath("$.calls").value(8));

        readBack();
        TrainRun run = runs.findByTimetableUidAndRunDate("X12345", DAY).orElseThrow();
        assertThat(run.getHeadcode()).isEqualTo("9Z99");
        assertThat(run.getOperator()).isEqualTo("Example Rail");
        assertThat(run.getSource()).isEqualTo("https://example.invalid/run");
        assertThat(run.getImportedAt()).isNotNull();
        assertThat(trainOf(first)).isEqualTo(run.getId());
        assertThat(calls.findByTrainRunIdOrderBySeq(run.getId())).extracting(TrainRunCall::getSeq,
                TrainRunCall::getLocation, TrainRunCall::getPlannedArrival, TrainRunCall::getPlannedDeparture,
                TrainRunCall::getPlannedPass, TrainRunCall::getActualArrival, TrainRunCall::getActualDeparture,
                TrainRunCall::getActualPass)
            .containsExactly(
                tuple(0, "Alpha Junction", null, at("2030-03-04T22:00:00Z"), null, null,
                    at("2030-03-04T22:01:30Z"), null),
                tuple(1, "Beta Parkway", at("2030-03-04T22:30:00Z"), at("2030-03-04T22:32:00Z"), null,
                    at("2030-03-04T22:31:30Z"), at("2030-03-04T22:34:00Z"), null),
                tuple(2, "Gamma Loop", null, null, at("2030-03-04T22:50:30Z"), null, null,
                    at("2030-03-04T22:53:00Z")),
                tuple(3, "Delta Cross", null, null, at("2030-03-04T23:10:00Z"), at("2030-03-04T23:12:00Z"),
                    at("2030-03-04T23:13:30Z"), null),
                tuple(4, "Epsilon Green", at("2030-03-04T23:40:00Z"), at("2030-03-04T23:41:00Z"), null, null,
                    null, null),
                tuple(5, "Zeta Sidings", null, null, at("2030-03-04T23:59:30Z"), null, null,
                    at("2030-03-05T00:02:00Z")),
                tuple(6, "Eta Works", null, null, at("2030-03-05T00:20:00Z"), null, null,
                    at("2030-03-05T00:22:30Z")),
                tuple(7, "Theta Central", at("2030-03-05T00:45:00Z"), null, null, at("2030-03-05T00:47:30Z"),
                    null, null));
    }

    private static Instant at(String text) {
        return Instant.parse(text);
    }

    @Test
    void theSameFileASecondTimeAnswers200AgainAndTheCallsAreThereOnce() throws Exception {
        give(first, example()).andExpect(status().isOk());
        readBack();

        give(first, example()).andExpect(status().isOk()).andExpect(jsonPath("$.calls").value(8));

        readBack();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(8);
        assertThat(trainOf(first)).isEqualTo(runs.findAll().getFirst().getId());
    }

    @Test
    void aFileWithAnotherHeadcodeAndNoOperatorReplacesTheHeadcodeAndKeepsTheOperator() throws Exception {
        give(first, example()).andExpect(status().isOk());
        readBack();
        ObjectNode again = example().put("headcode", "9Z98");
        again.remove("operator");

        give(first, again).andExpect(status().isOk()).andExpect(jsonPath("$.headcode").value("9Z98"));

        readBack();
        TrainRun run = runs.findAll().getFirst();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(run.getHeadcode()).isEqualTo("9Z98");
        assertThat(run.getOperator()).isEqualTo("Example Rail");
    }

    @Test
    void twoJourneysOnOneTrainShareTheRunAndTheLastToLetGoDeletesIt() throws Exception {
        give(first, example()).andExpect(status().isOk());
        give(second, example()).andExpect(status().isOk());
        readBack();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(trainOf(first)).isEqualTo(trainOf(second));

        takeAway(first).andExpect(status().isNoContent());

        readBack();
        assertThat(trainOf(first)).isNull();
        assertThat(trainOf(second)).isNotNull();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(8);

        takeAway(second).andExpect(status().isNoContent());
        takeAway(second).andExpect(status().isNoContent());

        readBack();
        assertThat(trainOf(second)).isNull();
        assertThat(runs.count()).isZero();
        assertThat(calls.count()).isZero();
    }

    @Test
    void takingTheTrainFromAJourneyThatHasNoneIs204AndLeavesTheOtherRunsAlone() throws Exception {
        give(second, example()).andExpect(status().isOk());
        readBack();
        Long runId = trainOf(second);

        takeAway(first).andExpect(status().isNoContent());

        readBack();
        assertThat(journeys.findById(first)).isPresent();
        assertThat(trainOf(first)).isNull();
        assertThat(trainOf(second)).isEqualTo(runId);
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(8);
    }

    @Test
    void aJourneyGivenAnotherTrainLosesTheFirstRun() throws Exception {
        give(first, example()).andExpect(status().isOk());
        readBack();
        ObjectNode other = example().put("uid", "X54321");

        give(first, other).andExpect(status().isOk());

        readBack();
        assertThat(runs.findAll()).extracting(TrainRun::getTimetableUid).containsExactly("X54321");
        assertThat(calls.count()).isEqualTo(8);
        assertThat(trainOf(first)).isEqualTo(runs.findAll().getFirst().getId());
    }

    @Test
    void aFileForAnotherDayIsA400AndLeavesTheStoredRowsAsTheyWere() throws Exception {
        give(first, example()).andExpect(status().isOk());
        readBack();
        Long runId = trainOf(first);

        give(first, example().put("date", "2030-03-09")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The train did not run during this journey"));

        readBack();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(8);
        assertThat(trainOf(first)).isEqualTo(runId);
    }

    @Test
    void aFileWithOtherEndPointsIsA409AndLeavesTheStoredRowsAsTheyWere() throws Exception {
        give(first, example()).andExpect(status().isOk());
        readBack();
        ObjectNode other = example().put("headcode", "9Z98");
        firstCall(other).put("location", "Omega Halt");

        give(first, other).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("This train is stored with other end points"));

        readBack();
        assertThat(runs.findAll()).extracting(TrainRun::getHeadcode).containsExactly("9Z99");
        assertThat(calls.findByTrainRunIdOrderBySeq(trainOf(first))).extracting(TrainRunCall::getLocation)
            .startsWith("Alpha Junction").hasSize(8);
    }

    @Test
    void anUnknownJourneyIsA404OnBothRoutes() throws Exception {
        give(9_999L, example()).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such journey"));
        takeAway(9_999L).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such journey"));
    }
}
