package gregochr.cinefilm.journey;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.journey.JourneyFileSamples.example;
import static gregochr.cinefilm.journey.JourneyFileSamples.firstSample;
import static gregochr.cinefilm.journey.JourneyFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gregochr.cinefilm.ApiTestBase;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;

/** The import end to end on H2: the contract's worked example posted, read back through the repositories. */
class JourneyImportApiTest extends ApiTestBase {

    private static final String URL = "/api/admin/journeys";
    private static final long RUN = 1_790_000_000_000L;

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
    @Autowired
    private JourneyPlaybackRepository links;
    @Autowired
    private PlaybackHealthRepository playbacks;
    @Autowired
    private EntityManager entityManager;

    private PlaybackHealth playback(String playbackId, long startedMs, long updatedMs, String environment) {
        PlaybackHealth row = JourneyTestData.playback(playbackId);
        row.setStartedAt(Instant.ofEpochMilli(startedMs));
        row.setUpdatedAt(Instant.ofEpochMilli(updatedMs));
        row.setEnvironment(environment);
        return playbacks.saveAndFlush(row);
    }

    private void post(ObjectNode body) throws Exception {
        mvc.perform(post(URL).content(text(body))).andExpect(status().isCreated());
    }

    private void readBack() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void theExampleAnswers201WithTheJourneysAndTheLinksOfTheOverlappingRecordsAwayFromHomeOnly() throws Exception {
        PlaybackHealth overlapping = playback("00000000-0000-4000-8000-000000000001", RUN + 60_000, RUN + 100_000,
            "train");
        playback("00000000-0000-4000-8000-000000000002", RUN + 60_000, RUN + 100_000, "home");
        playback("00000000-0000-4000-8000-000000000003", RUN + 3_600_000, RUN + 3_700_000, "train");
        PlaybackHealth noEnvironment = playback("00000000-0000-4000-8000-000000000004", RUN + 70_000, RUN + 110_000,
            null);

        mvc.perform(post(URL).content(text(example())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.journeys.length()").value(2))
            .andExpect(jsonPath("$.journeys[0].segment").value(0))
            .andExpect(jsonPath("$.journeys[0].trainUnit").value("Unit-001"))
            .andExpect(jsonPath("$.journeys[0].samples").value(4))
            .andExpect(jsonPath("$.journeys[0].from").value("2026-09-21T14:13:50Z"))
            .andExpect(jsonPath("$.journeys[0].to").value("2026-09-21T14:15:20Z"))
            .andExpect(jsonPath("$.journeys[0].linked").value(2))
            .andExpect(jsonPath("$.journeys[1].segment").value(1))
            .andExpect(jsonPath("$.journeys[1].trainUnit").value("Unit-002"))
            .andExpect(jsonPath("$.journeys[1].samples").value(2))
            .andExpect(jsonPath("$.journeys[1].linked").value(2));

        readBack();
        List<Journey> stored = journeys.findAllByOrderByRunStartedMsDescSegmentAsc();
        assertThat(stored).extracting(Journey::getSegment, Journey::getTrainUnit, Journey::getRunStartedMs)
            .containsExactly(tuple(0, "Unit-001", RUN), tuple(1, "Unit-002", RUN));
        assertThat(links.findByJourneyId(stored.get(0).getId())).extracting(JourneyPlayback::getPlaybackHealthId)
            .containsExactlyInAnyOrder(overlapping.getId(), noEnvironment.getId());
        assertThat(links.findByJourneyId(stored.get(1).getId())).extracting(JourneyPlayback::getPlaybackHealthId)
            .containsExactlyInAnyOrder(overlapping.getId(), noEnvironment.getId());
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,true", "1,true"})
    void aRecordIsLinkedWhenItEndsAtOrAfterTheWindowStartToTheMillisecond(long endsAtMs, boolean linked) {
        long from = RUN;
        PlaybackHealth record = playback("00000000-0000-4000-8000-000000000005", from - 3_600_000,
            from + endsAtMs, "train");

        assertThat(playbacks.idsOverlapping(Instant.ofEpochMilli(from), Instant.ofEpochMilli(from + 3_600_000),
            PageRequest.of(0, 200)).contains(record.getId())).isEqualTo(linked);
    }

    @ParameterizedTest
    @CsvSource({"1,false", "0,true", "-1,true"})
    void aRecordIsLinkedWhenItStartsAtOrBeforeTheWindowEndToTheMillisecond(long startsAtMs, boolean linked) {
        long to = RUN + 3_600_000;
        PlaybackHealth record = playback("00000000-0000-4000-8000-000000000006", to + startsAtMs,
            to + 3_600_000, "train");

        assertThat(playbacks.idsOverlapping(Instant.ofEpochMilli(RUN), Instant.ofEpochMilli(to),
            PageRequest.of(0, 200)).contains(record.getId())).isEqualTo(linked);
    }

    @Test
    void theJourneysDeviceAndFactsReadBack() throws Exception {
        post(example());

        readBack();
        List<Device> storedDevices = devices.findAll();
        assertThat(storedDevices).hasSize(1);
        assertThat(storedDevices.getFirst()).usingRecursiveComparison().ignoringFields("id")
            .isEqualTo(JourneyTestData.device("Testbook1,1"));
        List<Journey> stored = journeys.findAllByOrderByRunStartedMsDescSegmentAsc();
        assertThat(stored).extracting(Journey::getDeviceId).containsOnly(storedDevices.getFirst().getId());
        assertThat(stored).extracting(Journey::getOsVersion, Journey::getTravelClass, Journey::getSourceFile,
            Journey::getLoggerVersion, Journey::getImportedAt).containsOnly(tuple("TestOS 1.2", "standard",
                "test-feed-2026-09-21-1000.jsonl", 3, NOW));
    }

    @Test
    void everyColumnOfTheFullRoundReadsBack() throws Exception {
        post(example());

        readBack();
        Long journeyId = journeys.findAllByOrderByRunStartedMsDescSegmentAsc().getFirst().getId();
        List<JourneySample> stored = samples.findByJourneyIdOrderByRoundNo(journeyId);
        assertThat(stored).extracting(JourneySample::getRoundNo).containsExactly(1, 2, 3, 4);
        assertThat(stored.getFirst()).usingRecursiveComparison().ignoringFields("stored")
            .isEqualTo(expectedFullRound(journeyId));
        JourneySample failed = stored.get(2);
        assertThat(failed.getSampledAt()).isEqualTo(Instant.ofEpochMilli(RUN + 90_000));
        assertThat(failed.getLatitude()).isNull();
        assertThat(failed.getUsersOnline()).isNull();
    }

    private static JourneySample expectedFullRound(Long journeyId) {
        JourneySample full = new JourneySample();
        full.setJourneyId(journeyId);
        full.setRoundNo(1);
        full.setSampledAt(Instant.ofEpochMilli(RUN + 30_000));
        full.setFeedAt(Instant.ofEpochSecond(1_790_000_029L));
        full.setFixMode(3);
        full.setFixAgeS(4);
        full.setLatitude(12.5);
        full.setLongitude(34.5);
        full.setAltitudeM(56.5);
        full.setSpeedMps(21.5);
        full.setCourseDeg(187.5);
        full.setSatellites(9);
        full.setOnline(1);
        full.setUsersOnline(11);
        full.setUsersTotal(12);
        full.setDownLimit(625_000L);
        full.setUpLimit(125_000L);
        full.setCapLevel(15);
        full.setUserClass(16);
        full.setDataDownUsed(1_700L);
        full.setDataUpUsed(1_800L);
        full.setProbeMs(19);
        full.setProbeCode(204);
        full.setPowerSource("battery");
        full.setBatteryPercent(82);
        full.setLowPower(0);
        full.setCpuLimit(100);
        full.setLoadAvg(2.5);
        full.setSwapMb(26.5);
        full.setWifiRssi(-57);
        full.setWifiNoise(-91);
        full.setWifiRate(286.5);
        full.setWifiPhy(8);
        full.setWifiBand(2);
        full.setWifiWidth(3);
        full.setWifiChannel(36);
        return full;
    }

    @Test
    void theModemsAndTheirStatesReadBackWithTheNullsTheFileGave() throws Exception {
        post(example());

        readBack();
        List<Journey> stored = journeys.findAllByOrderByRunStartedMsDescSegmentAsc();
        Long first = stored.get(0).getId();
        Long second = stored.get(1).getId();
        assertThat(modems.findByJourneyIdOrderByModemIndex(first)).extracting(JourneyModem::getModemIndex,
            JourneyModem::getDeviceType, JourneyModem::getDeviceSubtype)
            .containsExactly(tuple(100, "TestModemType", "TestSubtype"), tuple(101, "TestModemType", "TestSubtype"));
        assertThat(modems.findByJourneyIdOrderByModemIndex(second)).extracting(JourneyModem::getModemIndex)
            .containsExactly(100);
        List<JourneyModemSample> states = modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(first);
        assertThat(states).extracting(JourneyModemSample::getRoundNo, JourneyModemSample::getModemIndex)
            .containsExactly(tuple(1, 100), tuple(1, 101), tuple(2, 100), tuple(4, 101));
        assertThat(states.get(0)).extracting(JourneyModemSample::getDeviceState, JourneyModemSample::getLinkState,
            JourneyModemSample::getRssi, JourneyModemSample::getTechnology, JourneyModemSample::getOperatorId,
            JourneyModemSample::getCellId).containsExactly("up", "available", -70, "lte", "00123", 123_456L);
        assertThat(states.get(1)).extracting(JourneyModemSample::getDeviceState, JourneyModemSample::getLinkState,
            JourneyModemSample::getRssi, JourneyModemSample::getTechnology, JourneyModemSample::getOperatorId,
            JourneyModemSample::getCellId).containsExactly("down", "disconnected", null, null, null, null);
        assertThat(modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(second))
            .extracting(JourneyModemSample::getRoundNo).containsExactly(5);
    }

    @Test
    void postingTheSameFileAgainIs409AndAddsNothing() throws Exception {
        post(example());
        long journeyCount = journeys.count();
        long sampleCount = samples.count();
        long modemCount = modems.count();
        long deviceCount = devices.count();

        mvc.perform(post(URL).content(text(example())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("Already imported"));

        assertThat(journeys.count()).isEqualTo(journeyCount).isEqualTo(2);
        assertThat(samples.count()).isEqualTo(sampleCount).isEqualTo(6);
        assertThat(modems.count()).isEqualTo(modemCount).isEqualTo(3);
        assertThat(devices.count()).isEqualTo(deviceCount).isEqualTo(1);
    }

    @Test
    void theSameFileWithOneValueOutOfRangeIs400AndStoresNothingAtAll() throws Exception {
        ObjectNode tree = example();
        firstSample(tree).put("latitude", 91.0);

        mvc.perform(post(URL).content(text(tree)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid journey file"));

        assertThat(journeys.count()).isZero();
        assertThat(samples.count()).isZero();
        assertThat(modems.count()).isZero();
        assertThat(modemSamples.count()).isZero();
        assertThat(devices.count()).isZero();
        assertThat(links.count()).isZero();
    }

    @Test
    void aNameTheServerDoesNotKnowIs400AndStoresNothing() throws Exception {
        ObjectNode tree = example();
        firstSample(tree).put("macAddress", "00:00:00:00:00:00");

        mvc.perform(post(URL).content(text(tree)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Invalid journey file"));

        assertThat(journeys.count()).isZero();
        assertThat(devices.count()).isZero();
    }

    @Test
    void aSecondFileWithTheSameDeviceReusesIt() throws Exception {
        post(example());
        ObjectNode other = example();
        other.put("runStartedMs", RUN + 86_400_000L);

        post(other);

        assertThat(devices.count()).isEqualTo(1);
        assertThat(journeys.count()).isEqualTo(4);
    }
}
