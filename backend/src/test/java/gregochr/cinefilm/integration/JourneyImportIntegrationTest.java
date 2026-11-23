package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.journey.DeviceRepository;
import gregochr.cinefilm.journey.JourneyModemRepository;
import gregochr.cinefilm.journey.JourneyModemSampleRepository;
import gregochr.cinefilm.journey.JourneyPlaybackRepository;
import gregochr.cinefilm.journey.JourneyRepository;
import gregochr.cinefilm.journey.JourneySampleRepository;
import gregochr.cinefilm.journey.JourneyTestData;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The journey import on PostgreSQL, committed like production: the contract's example, a repeat and a big file. */
@AutoConfigureMockMvc
class JourneyImportIntegrationTest extends IntegrationTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long RUN = 1_790_000_000_000L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JourneyRepository journeys;
    @Autowired
    private DeviceRepository devices;
    @Autowired
    private JourneySampleRepository samples;
    @Autowired
    private JourneyModemRepository modems;
    @Autowired
    private JourneyModemSampleRepository modemSamples;
    @MockitoSpyBean
    private JourneyPlaybackRepository links;
    @Autowired
    private PlaybackHealthRepository health;

    @BeforeEach
    void clean() {
        links.deleteAll();
        journeys.deleteAll();
        devices.deleteAll();
        health.deleteAll();
    }

    private static ObjectNode example() throws IOException {
        try (InputStream in = JourneyImportIntegrationTest.class
            .getResourceAsStream("/journey/import-request-example.json")) {
            return (ObjectNode) JSON.readTree(in);
        }
    }

    private ResultActions post(ObjectNode body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/admin/journeys").with(user("owner").roles("OWNER"))
            .header("X-CineFilm", "1").contentType("application/json").content(JSON.writeValueAsString(body)));
    }

    @Test
    void theExampleCommitsAndReadsBackWithTheLinkOfTheOverlappingRecord() throws Exception {
        PlaybackHealth record = JourneyTestData.playback("00000000-0000-4000-8000-000000000001");
        record.setStartedAt(Instant.ofEpochMilli(RUN + 60_000));
        record.setUpdatedAt(Instant.ofEpochMilli(RUN + 100_000));
        record.setEnvironment("train");
        health.saveAndFlush(record);

        post(example()).andExpect(status().isCreated())
            .andExpect(jsonPath("$.journeys.length()").value(2))
            .andExpect(jsonPath("$.journeys[0].linked").value(1));

        assertThat(journeys.count()).isEqualTo(2);
        assertThat(samples.count()).isEqualTo(6);
        assertThat(modems.count()).isEqualTo(3);
        assertThat(modemSamples.count()).isEqualTo(5);
        assertThat(devices.count()).isEqualTo(1);
        assertThat(links.count()).isEqualTo(2);
    }

    @Test
    void anImportThatFailsOnItsLastWriteStoresNothingAtAll() throws Exception {
        PlaybackHealth record = JourneyTestData.playback("00000000-0000-4000-8000-000000000001");
        record.setStartedAt(Instant.ofEpochMilli(RUN + 60_000));
        record.setUpdatedAt(Instant.ofEpochMilli(RUN + 100_000));
        record.setEnvironment("train");
        health.saveAndFlush(record);
        doThrow(new DataIntegrityViolationException("the last write fails")).when(links).saveAllAndFlush(anyList());

        post(example()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Try again"));

        assertThat(journeys.count()).isZero();
        assertThat(samples.count()).isZero();
        assertThat(modems.count()).isZero();
        assertThat(modemSamples.count()).isZero();
        assertThat(devices.count()).isZero();
        assertThat(health.count()).isEqualTo(1);
    }

    @Test
    void thePostOfTheSameFileAgainIs409AndAddsNothing() throws Exception {
        post(example()).andExpect(status().isCreated());

        post(example()).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Already imported"));

        assertThat(journeys.count()).isEqualTo(2);
        assertThat(samples.count()).isEqualTo(6);
    }

    @Test
    void aFileOfTwoThousandRoundsWithFourModemsEachIsStored() throws Exception {
        ObjectNode tree = example();
        ArrayNode list = (ArrayNode) tree.get("samples");
        list.removeAll();
        for (int round = 1; round <= 2_000; round++) {
            ObjectNode sample = list.addObject().put("round", round).put("at", RUN + 30_000L * round)
                .put("trainUnit", "Unit-001").put("latitude", 12.5).put("longitude", 34.5);
            ArrayNode modemList = sample.putArray("modems");
            for (int index = 100; index < 104; index++) {
                modemList.addObject().put("index", index).put("deviceType", "TestModemType")
                    .put("deviceSubtype", "TestSubtype").put("deviceState", "up").put("linkState", "available")
                    .put("technology", "lte").put("rssi", -70).put("operatorId", "00123").put("cellId", 123_456);
            }
        }

        post(tree).andExpect(status().isCreated())
            .andExpect(jsonPath("$.journeys.length()").value(1))
            .andExpect(jsonPath("$.journeys[0].samples").value(2_000));

        assertThat(samples.count()).isEqualTo(2_000);
        assertThat(modems.count()).isEqualTo(4);
        assertThat(modemSamples.count()).isEqualTo(8_000);
    }
}
