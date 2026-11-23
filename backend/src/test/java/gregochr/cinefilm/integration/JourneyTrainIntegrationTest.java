package gregochr.cinefilm.integration;

import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.journey.JourneyRepository;
import gregochr.cinefilm.journey.JourneySample;
import gregochr.cinefilm.journey.JourneySampleRepository;
import gregochr.cinefilm.journey.TrainRun;
import gregochr.cinefilm.journey.TrainRunCallRepository;
import gregochr.cinefilm.journey.TrainRunRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Giving journeys a train on PostgreSQL, committed like production: a repeat, a shared run and the sweep. */
@AutoConfigureMockMvc
class JourneyTrainIntegrationTest extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JourneyRepository journeys;
    @Autowired
    private JourneySampleRepository samples;
    @Autowired
    private TrainRunRepository runs;
    @Autowired
    private TrainRunCallRepository calls;
    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        journeys.deleteAll();
        runs.deleteAll();
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

    private static String file(String uid) throws IOException {
        try (InputStream in = JourneyTrainIntegrationTest.class
            .getResourceAsStream("/journey/train-file-example.json")) {
            return JSON.writeValueAsString(((ObjectNode) JSON.readTree(in)).put("uid", uid));
        }
    }

    private ResultActions give(Long journeyId) throws Exception {
        return give(journeyId, "X12345");
    }

    private ResultActions give(Long journeyId, String uid) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/admin/journeys/" + journeyId + "/train")
            .with(user("owner").roles("OWNER")).header("X-CineFilm", "1").contentType("application/json")
            .content(file(uid)));
    }

    private ResultActions takeAway(Long journeyId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/admin/journeys/" + journeyId + "/train")
            .with(user("owner").roles("OWNER")).header("X-CineFilm", "1"));
    }

    @Test
    void theSameFileTwiceAndASharedRunKeptUntilTheLastJourneyLetsGo() throws Exception {
        Long first = journeyWithSamples(1_000L);
        Long second = journeyWithSamples(2_000L);

        give(first).andExpect(status().isOk());
        give(first).andExpect(status().isOk());
        give(second).andExpect(status().isOk());

        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(8);

        takeAway(first).andExpect(status().isNoContent());
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(8);

        takeAway(second).andExpect(status().isNoContent());
        assertThat(runs.count()).isZero();
        assertThat(calls.count()).isZero();
    }

    @Test
    void aJourneyGivenAnotherTrainKeepsOnlyTheNewRunAndItsCalls() throws Exception {
        Long id = journeyWithSamples(1_000L);
        give(id, "X12345").andExpect(status().isOk());

        give(id, "Y54321").andExpect(status().isOk());

        assertThat(runs.findAll()).extracting(TrainRun::getTimetableUid).containsExactly("Y54321");
        Long runId = runs.findAll().getFirst().getId();
        assertThat(journeys.findById(id).orElseThrow().getTrainRunId()).isEqualTo(runId);
        assertThat(calls.count()).isEqualTo(8);
        assertThat(calls.findByTrainRunIdOrderBySeq(runId)).hasSize(8);
    }

    @Test
    void theTwoLockedFindersRunInATransaction() throws Exception {
        Long id = journeyWithSamples(1_000L);
        give(id).andExpect(status().isOk());

        Boolean found = transactions.execute(status ->
            journeys.findLockedById(id).isPresent()
                && runs.findByTimetableUidAndRunDate("X12345", LocalDate.of(2030, 3, 4)).isPresent());

        assertThat(found).isTrue();
    }
}
