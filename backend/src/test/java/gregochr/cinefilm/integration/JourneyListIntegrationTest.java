package gregochr.cinefilm.integration;

import static gregochr.cinefilm.journey.JourneyTestData.call;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.link;
import static gregochr.cinefilm.journey.JourneyTestData.modem;
import static gregochr.cinefilm.journey.JourneyTestData.modemSample;
import static gregochr.cinefilm.journey.JourneyTestData.playback;
import static gregochr.cinefilm.journey.JourneyTestData.sample;
import static gregochr.cinefilm.journey.JourneyTestData.trainRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.journey.Journey;
import gregochr.cinefilm.journey.JourneyModemRepository;
import gregochr.cinefilm.journey.JourneyModemSampleRepository;
import gregochr.cinefilm.journey.JourneyPlaybackRepository;
import gregochr.cinefilm.journey.JourneyRepository;
import gregochr.cinefilm.journey.JourneySampleRepository;
import gregochr.cinefilm.journey.LinkCount;
import gregochr.cinefilm.journey.SampleStats;
import gregochr.cinefilm.journey.TrainRunCallRepository;
import gregochr.cinefilm.journey.TrainRunRepository;
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

/** The two grouped queries of the list and the journey delete on PostgreSQL, committed like production. */
@AutoConfigureMockMvc
class JourneyListIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;
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
    private PlaybackHealthRepository health;
    @Autowired
    private TrainRunRepository runs;
    @Autowired
    private TrainRunCallRepository calls;

    @BeforeEach
    void clean() {
        links.deleteAll();
        journeys.deleteAll();
        runs.deleteAll();
        health.deleteAll();
    }

    private Long journeyWithRows(long runStartedMs, Long trainRunId) {
        Journey made = journey(runStartedMs, 1, null);
        made.setTrainRunId(trainRunId);
        Long id = journeys.saveAndFlush(made).getId();
        samples.saveAllAndFlush(List.of(sample(id, 1), sample(id, 2)));
        modems.saveAndFlush(modem(id, 0));
        modemSamples.saveAndFlush(modemSample(id, 1, 0));
        return id;
    }

    private ResultActions deleteJourney(Long id) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/admin/journeys/" + id)
            .with(user("owner").roles("OWNER")).header("X-CineFilm", "1"));
    }

    @Test
    void theTwoGroupedQueriesCountPerJourneyAndLeaveOutAJourneyWithNone() {
        Long with = journeyWithRows(1_000L, null);
        journeys.saveAndFlush(journey(2_000L, 1, null));
        Long record = health.saveAndFlush(playback("00000000-0000-4000-8000-00000000000a")).getId();
        links.saveAndFlush(link(with, record));

        assertThat(samples.statsOfEveryJourney()).containsExactly(new SampleStats(with, 2,
            Instant.parse("2024-03-31T23:30:30Z"), Instant.parse("2024-03-31T23:31:00Z")));
        assertThat(links.countsOfEveryJourney()).containsExactly(new LinkCount(with, 1));
    }

    @Test
    void theListThroughTheRouteHasTheCountsOfEachJourneyNewestRunFirst() throws Exception {
        Long with = journeyWithRows(1_000L, null);
        journeys.saveAndFlush(journey(2_000L, 1, null));

        mvc.perform(MockMvcRequestBuilders.get("/api/admin/journeys").with(user("owner").roles("OWNER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.journeys.length()").value(2))
            .andExpect(jsonPath("$.journeys[0].samples").value(0))
            .andExpect(jsonPath("$.journeys[1].id").value(with))
            .andExpect(jsonPath("$.journeys[1].samples").value(2));
    }

    @Test
    void aDeleteTakesTheJourneyItsRowsAndItsLinksAndASharedRunOutlivesTheFirstDelete() throws Exception {
        Long run = runs.saveAndFlush(trainRun("X12345", LocalDate.of(2030, 3, 4))).getId();
        calls.saveAndFlush(call(run, 0));
        Long first = journeyWithRows(1_000L, run);
        Long second = journeyWithRows(2_000L, run);
        Long record = health.saveAndFlush(playback("00000000-0000-4000-8000-00000000000b")).getId();
        links.saveAndFlush(link(first, record));

        deleteJourney(first).andExpect(status().isNoContent());

        assertThat(journeys.findById(first)).isEmpty();
        assertThat(samples.findByJourneyIdOrderByRoundNo(first)).isEmpty();
        assertThat(modems.findByJourneyIdOrderByModemIndex(first)).isEmpty();
        assertThat(modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(first)).isEmpty();
        assertThat(links.findByJourneyId(first)).isEmpty();
        assertThat(samples.findByJourneyIdOrderByRoundNo(second)).hasSize(2);
        assertThat(runs.count()).isEqualTo(1);
        assertThat(calls.count()).isEqualTo(1);

        deleteJourney(second).andExpect(status().isNoContent());

        assertThat(runs.count()).isZero();
        assertThat(calls.count()).isZero();
    }
}
