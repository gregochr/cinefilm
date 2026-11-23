package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyFileSamples.example;
import static gregochr.cinefilm.journey.JourneyFileSamples.samples;
import static gregochr.cinefilm.journey.JourneyFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The list of journeys and the delete, end to end on H2: two files imported through the API (so the list reads what
 * the import wrote), one journey given a train, and everything read back through the repositories.
 */
class JourneyListApiTest extends ApiTestBase {

    private static final String URL = "/api/admin/journeys";
    private static final long FIRST_RUN = 1_790_000_000_000L;
    private static final long DAY_MS = 86_400_000L;

    @Autowired
    private JourneyRepository journeys;
    @Autowired
    private JourneySampleRepository samples;
    @Autowired
    private JourneyModemRepository modems;
    @Autowired
    private JourneyModemSampleRepository modemSamples;
    @Autowired
    private JourneyPlaybackRepository links;
    @Autowired
    private PlaybackHealthRepository playbacks;
    @Autowired
    private TrainRunRepository runs;
    @Autowired
    private TrainRunCallRepository calls;
    @Autowired
    private EntityManager entityManager;

    private Long firstOfFirstFile;
    private Long secondOfFirstFile;
    private Long firstOfSecondFile;
    private Long secondOfSecondFile;

    @BeforeEach
    void twoFilesImportedAndOneRecordLinkedToTheFirst() throws Exception {
        PlaybackHealth record = JourneyTestData.playback("00000000-0000-4000-8000-000000000001");
        record.setStartedAt(Instant.ofEpochMilli(FIRST_RUN + 60_000));
        record.setUpdatedAt(Instant.ofEpochMilli(FIRST_RUN + 100_000));
        record.setEnvironment("train");
        playbacks.saveAndFlush(record);
        post(example());
        post(secondFile());
        readBack();
        List<Journey> stored = journeys.findAllByOrderByRunStartedMsDescSegmentAsc();
        firstOfSecondFile = stored.get(0).getId();
        secondOfSecondFile = stored.get(1).getId();
        firstOfFirstFile = stored.get(2).getId();
        secondOfFirstFile = stored.get(3).getId();
    }

    /** The example a day later, from another file, on no device and with no travel class. */
    private static ObjectNode secondFile() {
        ObjectNode tree = example();
        tree.put("runStartedMs", FIRST_RUN + DAY_MS);
        tree.put("sourceFile", "test-feed-2026-09-22-1000.jsonl");
        tree.remove("device");
        tree.remove("travelClass");
        for (JsonNode sample : samples(tree)) {
            ((ObjectNode) sample).put("at", sample.get("at").asLong() + DAY_MS);
        }
        return tree;
    }

    private ResultActions post(ObjectNode body) throws Exception {
        return mvc.perform(post(URL).content(text(body)));
    }

    private ResultActions deleteJourney(Long id) throws Exception {
        return mvc.perform(authed(MockMvcRequestBuilders.delete(URL + "/{id}", id)));
    }

    private void giveTrain(Long journeyId, String uid) throws Exception {
        String file = """
            {"uid":"%s","date":"2026-09-21","headcode":"9Z99","operator":"Example Rail",
             "calls":[{"location":"Alpha Junction","plannedDeparture":"15:10"},
                      {"location":"Beta Parkway","plannedArrival":"15:40"}]}
            """.formatted(uid);
        mvc.perform(put(URL + "/{id}/train", journeyId).content(file)).andExpect(status().isOk());
    }

    private void readBack() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void theListHasEveryJourneyNewestRunFirstWithEveryValue() throws Exception {
        giveTrain(firstOfFirstFile, "Y22222");
        readBack();

        mvc.perform(get(URL))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(content().json("""
                {"journeys":[
                  {"id":%d,"runStartedMs":1790086400000,"segment":0,"trainUnit":"Unit-001","travelClass":null,
                   "osVersion":"TestOS 1.2","loggerVersion":3,"sourceFile":"test-feed-2026-09-22-1000.jsonl",
                   "importedAt":"2024-03-31T23:30:00Z","device":null,"samples":4,
                   "from":"2026-09-22T14:13:50Z","to":"2026-09-22T14:15:20Z","linked":0,"train":null},
                  {"id":%d,"runStartedMs":1790086400000,"segment":1,"trainUnit":"Unit-002","travelClass":null,
                   "osVersion":"TestOS 1.2","loggerVersion":3,"sourceFile":"test-feed-2026-09-22-1000.jsonl",
                   "importedAt":"2024-03-31T23:30:00Z","device":null,"samples":2,
                   "from":"2026-09-22T14:15:50Z","to":"2026-09-22T14:16:20Z","linked":0,"train":null},
                  {"id":%d,"runStartedMs":1790000000000,"segment":0,"trainUnit":"Unit-001",
                   "travelClass":"standard","osVersion":"TestOS 1.2","loggerVersion":3,
                   "sourceFile":"test-feed-2026-09-21-1000.jsonl","importedAt":"2024-03-31T23:30:00Z",
                   "device":{"model":"Testbook1,1","cpu":"Test Chip One","cores":8,"threads":8,
                             "memoryBytes":17179869184},
                   "samples":4,"from":"2026-09-21T14:13:50Z","to":"2026-09-21T14:15:20Z","linked":1,
                   "train":{"uid":"Y22222","date":"2026-09-21","headcode":"9Z99"}},
                  {"id":%d,"runStartedMs":1790000000000,"segment":1,"trainUnit":"Unit-002",
                   "travelClass":"standard","osVersion":"TestOS 1.2","loggerVersion":3,
                   "sourceFile":"test-feed-2026-09-21-1000.jsonl","importedAt":"2024-03-31T23:30:00Z",
                   "device":{"model":"Testbook1,1","cpu":"Test Chip One","cores":8,"threads":8,
                             "memoryBytes":17179869184},
                   "samples":2,"from":"2026-09-21T14:15:50Z","to":"2026-09-21T14:16:20Z","linked":1,
                   "train":null}]}
                """.formatted(firstOfSecondFile, secondOfSecondFile, firstOfFirstFile, secondOfFirstFile), true));
    }

    @Test
    void aDeletedJourneyIsGoneFromTheListAndTakesItsRowsWhileTheOthersKeepTheirs() throws Exception {
        assertThat(samples.findByJourneyIdOrderByRoundNo(firstOfFirstFile)).hasSize(4);
        assertThat(modems.findByJourneyIdOrderByModemIndex(firstOfFirstFile)).isNotEmpty();
        assertThat(modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(firstOfFirstFile)).isNotEmpty();
        assertThat(links.findByJourneyId(firstOfFirstFile)).hasSize(1);
        long otherSamples = samples.count() - 4;
        long otherModems = modems.count() - modems.findByJourneyIdOrderByModemIndex(firstOfFirstFile).size();
        long otherModemSamples = modemSamples.count()
            - modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(firstOfFirstFile).size();
        long otherLinks = links.count() - 1;

        deleteJourney(firstOfFirstFile).andExpect(status().isNoContent()).andExpect(content().string(""));

        readBack();
        mvc.perform(get(URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.journeys.length()").value(3))
            .andExpect(jsonPath("$.journeys[?(@.id == %d)]", firstOfFirstFile).isEmpty());
        assertThat(journeys.findById(firstOfFirstFile)).isEmpty();
        assertThat(samples.findByJourneyIdOrderByRoundNo(firstOfFirstFile)).isEmpty();
        assertThat(modems.findByJourneyIdOrderByModemIndex(firstOfFirstFile)).isEmpty();
        assertThat(modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(firstOfFirstFile)).isEmpty();
        assertThat(links.findByJourneyId(firstOfFirstFile)).isEmpty();
        assertThat(samples.count()).isEqualTo(otherSamples);
        assertThat(modems.count()).isEqualTo(otherModems);
        assertThat(modemSamples.count()).isEqualTo(otherModemSamples);
        assertThat(links.count()).isEqualTo(otherLinks);
        assertThat(links.findByJourneyId(secondOfFirstFile)).hasSize(1);
        assertThat(playbacks.count()).isEqualTo(1);
    }

    @Test
    void aRunSharedByTwoJourneysOutlivesTheFirstDeleteAndGoesWithTheSecond() throws Exception {
        giveTrain(firstOfFirstFile, "Y22222");
        giveTrain(secondOfFirstFile, "Y22222");
        readBack();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(2);

        deleteJourney(firstOfFirstFile).andExpect(status().isNoContent());

        readBack();
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(2);

        deleteJourney(secondOfFirstFile).andExpect(status().isNoContent());

        readBack();
        assertThat(runs.count()).isZero();
        assertThat(calls.count()).isZero();
    }

    @Test
    void aFileIsRefusedAsAlreadyImportedUntilItsJourneysAreDeletedAndImportsAgainAfter() throws Exception {
        post(example()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Already imported"));

        deleteJourney(firstOfFirstFile).andExpect(status().isNoContent());
        deleteJourney(secondOfFirstFile).andExpect(status().isNoContent());
        readBack();

        post(example()).andExpect(status().isCreated()).andExpect(jsonPath("$.journeys.length()").value(2));
    }

    @Test
    void aDeleteOfAnUnknownJourneyIsA404() throws Exception {
        deleteJourney(999_999L)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("No such journey"));
    }

    @Test
    void aJourneyAloneOnItsTrainTakesItsRunAndCallsWithItAndLeavesTheOtherRunsAlone() throws Exception {
        giveTrain(firstOfFirstFile, "Y22222");
        giveTrain(secondOfFirstFile, "Z33333");
        readBack();
        assertThat(runs.count()).isEqualTo(2);

        deleteJourney(firstOfFirstFile).andExpect(status().isNoContent());

        readBack();
        assertThat(runs.findAll()).extracting(TrainRun::getTimetableUid).containsExactly("Z33333");
        assertThat(calls.count()).isEqualTo(2);
    }

    @Test
    void aJourneyWithNoTrainDeletedLeavesTheStoredRunsOfOtherJourneysUntouched() throws Exception {
        giveTrain(firstOfFirstFile, "Y22222");
        readBack();

        deleteJourney(secondOfFirstFile).andExpect(status().isNoContent());

        readBack();
        assertThat(runs.findAll()).extracting(TrainRun::getTimetableUid).containsExactly("Y22222");
        assertThat(calls.count()).isEqualTo(2);
        assertThat(journeys.findById(firstOfFirstFile).orElseThrow().getTrainRunId())
            .isEqualTo(runs.findAll().getFirst().getId());
    }
}
