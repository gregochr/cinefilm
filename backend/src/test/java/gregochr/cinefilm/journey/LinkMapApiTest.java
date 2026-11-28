package gregochr.cinefilm.journey;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The link map end to end on H2: two stored journeys with made-up positions in an ocean, whose rounds are good or
 * bad by the logger's internet check alone, read through the real repositories and the real route.
 */
class LinkMapApiTest extends ApiTestBase {

    private static final String URL = "/api/journeys/link-map";

    @Autowired
    private JourneyRepository journeys;
    @Autowired
    private JourneySampleRepository samples;

    private Long withABadRound;

    @BeforeEach
    void twoJourneys() {
        withABadRound = journey(1_000L, 0.5, 100, 5_000, 100);
        journey(2_000L, 0.6, 100, 100, 100);
    }

    private Long journey(long runStartedMs, double latitude, int... probes) {
        Long id = journeys.saveAndFlush(JourneyTestData.journey(runStartedMs, 0, null)).getId();
        for (int round = 0; round < probes.length; round++) {
            JourneySample sample = JourneyTestData.sample(id, round);
            sample.setLatitude(latitude + round * 0.005);
            sample.setLongitude(-20.0);
            sample.setProbeMs(probes[round]);
            samples.saveAndFlush(sample);
        }
        return id;
    }

    @Test
    void theMapHasEveryTrackWithItsPointsAndStretchesAndNothingThatNamesAJourney() throws Exception {
        mvc.perform(get(URL))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().json("""
                {"tracks":[
                  {"points":[{"latitude":0.6,"longitude":-20.0,"courseDeg":null,"t":0},
                             {"latitude":0.605,"longitude":-20.0,"courseDeg":null,"t":30},
                             {"latitude":0.61,"longitude":-20.0,"courseDeg":null,"t":60}],
                   "stretches":[]},
                  {"points":[{"latitude":0.5,"longitude":-20.0,"courseDeg":null,"t":0},
                             {"latitude":0.505,"longitude":-20.0,"courseDeg":null,"t":30},
                             {"latitude":0.51,"longitude":-20.0,"courseDeg":null,"t":60}],
                   "stretches":[{"from":30,"to":30,"need":135.0}]}]}
                """, true));
    }

    @Test
    void excludeJourneyLeavesThatJourneyOut() throws Exception {
        mvc.perform(get(URL + "?excludeJourney=" + withABadRound))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"tracks":[
                  {"points":[{"latitude":0.6,"longitude":-20.0,"courseDeg":null,"t":0},
                             {"latitude":0.605,"longitude":-20.0,"courseDeg":null,"t":30},
                             {"latitude":0.61,"longitude":-20.0,"courseDeg":null,"t":60}],
                   "stretches":[]}]}
                """, true));
    }

    @Test
    void signedOutIsA401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void noJourneyIsAnEmptyMap() throws Exception {
        samples.deleteAll();
        journeys.deleteAll();
        samples.flush();
        journeys.flush();

        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(content().json("{\"tracks\":[]}", true));
    }
}
