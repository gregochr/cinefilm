package gregochr.cinefilm.journey;

import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.journey.JourneyTestData.device;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.fullModemSample;
import static gregochr.cinefilm.journey.JourneyTestData.fullSample;
import static gregochr.cinefilm.journey.JourneyTestData.modem;
import static gregochr.cinefilm.journey.JourneyTestData.modemSample;
import static gregochr.cinefilm.journey.JourneyTestData.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The rounds of a journey and its modems: round-trips, the keys, the order and the cascade. */
class JourneySampleRepositoryTest extends ApiTestBase {

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
    private EntityManager entityManager;

    private Long newJourney() {
        return journeys.saveAndFlush(journey(1_000_000L, 1, null)).getId();
    }

    @Test
    void aSampleWithEveryColumnFilledReadsBackWithEveryColumn() {
        Long journeyId = newJourney();
        samples.saveAndFlush(fullSample(journeyId));
        entityManager.clear();

        JourneySample loaded = samples.findById(new JourneySampleId(journeyId, 7)).orElseThrow();

        JourneySample expected = fullSample(journeyId);
        assertThat(expected).hasNoNullFieldsOrProperties();
        assertThat(loaded).usingRecursiveComparison().ignoringFields("stored").isEqualTo(expected);
    }

    @Test
    void aRoundThatFailedStoresWithOnlyItsNumberAndTime() {
        Long journeyId = newJourney();
        samples.saveAndFlush(sample(journeyId, 1));
        entityManager.clear();

        JourneySample loaded = samples.findById(new JourneySampleId(journeyId, 1)).orElseThrow();

        assertThat(loaded.getSampledAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(loaded.getFeedAt()).isNull();
        assertThat(loaded.getLatitude()).isNull();
        assertThat(loaded.getUsersOnline()).isNull();
        assertThat(loaded.getPowerSource()).isNull();
        assertThat(loaded.getWifiWidth()).isNull();
    }

    @Test
    void theSameRoundOfAJourneyCannotBeStoredTwiceAndTheFirstIsUnchanged() {
        Long journeyId = newJourney();
        JourneySample first = sample(journeyId, 1);
        first.setLatitude(12.5);
        samples.saveAndFlush(first);
        entityManager.clear();
        JourneySample again = sample(journeyId, 1);
        again.setLatitude(99.5);

        assertThatThrownBy(() -> samples.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        assertThat(samples.findById(new JourneySampleId(journeyId, 1)).orElseThrow().getLatitude()).isEqualTo(12.5);
        assertThat(samples.count()).isEqualTo(1);
    }

    @Test
    void theSameModemOfAJourneyCannotBeStoredTwiceAndTheFirstIsUnchanged() {
        Long journeyId = newJourney();
        modems.saveAndFlush(modem(journeyId, 1));
        entityManager.clear();
        JourneyModem again = modem(journeyId, 1);
        again.setDeviceType("Changed");

        assertThatThrownBy(() -> modems.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        assertThat(modems.findById(new JourneyModemId(journeyId, 1)).orElseThrow().getDeviceType())
            .isEqualTo("TestModemType");
    }

    @Test
    void aModemSampleReadsBackWithEveryColumnAndTheSameKeyTwiceIsRefused() {
        Long journeyId = newJourney();
        samples.saveAndFlush(sample(journeyId, 1));
        modems.saveAndFlush(modem(journeyId, 0));
        modemSamples.saveAndFlush(fullModemSample(journeyId));
        entityManager.clear();
        JourneyModemSample again = modemSample(journeyId, 1, 0);
        again.setRssi(-1);

        assertThatThrownBy(() -> modemSamples.saveAndFlush(again)).isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        JourneyModemSample loaded = modemSamples.findById(new JourneyModemSampleId(journeyId, 1, 0)).orElseThrow();
        JourneyModemSample expected = fullModemSample(journeyId);
        assertThat(expected).hasNoNullFieldsOrProperties();
        assertThat(loaded).usingRecursiveComparison().ignoringFields("stored").isEqualTo(expected);
    }

    @Test
    void aSavedRowIsNotNewSoSavingTheSameInstanceAgainIsAnUpdate() {
        Long journeyId = newJourney();
        JourneySample sample = sample(journeyId, 1);
        JourneyModem modem = modem(journeyId, 0);
        JourneyModemSample modemSample = modemSample(journeyId, 1, 0);
        samples.saveAndFlush(sample);
        modems.saveAndFlush(modem);
        modemSamples.saveAndFlush(modemSample);
        assertThat(sample.isNew()).isFalse();
        assertThat(modem.isNew()).isFalse();
        assertThat(modemSample.isNew()).isFalse();
        assertThat(sample.getId()).isEqualTo(new JourneySampleId(journeyId, 1));
        assertThat(modem.getId()).isEqualTo(new JourneyModemId(journeyId, 0));
        assertThat(modemSample.getId()).isEqualTo(new JourneyModemSampleId(journeyId, 1, 0));
        entityManager.clear();

        sample.setLatitude(12.5);
        modem.setDeviceType("Changed");
        modemSample.setRssi(-1);
        samples.saveAndFlush(sample);
        modems.saveAndFlush(modem);
        modemSamples.saveAndFlush(modemSample);
        entityManager.clear();

        JourneySample loadedSample = samples.findById(sample.getId()).orElseThrow();
        JourneyModem loadedModem = modems.findById(modem.getId()).orElseThrow();
        JourneyModemSample loadedModemSample = modemSamples.findById(modemSample.getId()).orElseThrow();
        assertThat(loadedSample.getLatitude()).isEqualTo(12.5);
        assertThat(loadedModem.getDeviceType()).isEqualTo("Changed");
        assertThat(loadedModemSample.getRssi()).isEqualTo(-1);
        assertThat(loadedSample.isNew()).isFalse();
        assertThat(loadedModem.isNew()).isFalse();
        assertThat(loadedModemSample.isNew()).isFalse();
        assertThat(samples.count()).isEqualTo(1);
        assertThat(modems.count()).isEqualTo(1);
        assertThat(modemSamples.count()).isEqualTo(1);
    }

    @Test
    void samplesComeBackInRoundOrderAndModemsAndTheirSamplesInTheirOrder() {
        Long journeyId = newJourney();
        samples.saveAndFlush(sample(journeyId, 3));
        samples.saveAndFlush(sample(journeyId, 1));
        samples.saveAndFlush(sample(journeyId, 2));
        modems.saveAndFlush(modem(journeyId, 1));
        modems.saveAndFlush(modem(journeyId, 0));
        modemSamples.saveAndFlush(modemSample(journeyId, 2, 1));
        modemSamples.saveAndFlush(modemSample(journeyId, 1, 1));
        modemSamples.saveAndFlush(modemSample(journeyId, 2, 0));
        modemSamples.saveAndFlush(modemSample(journeyId, 1, 0));
        entityManager.clear();

        assertThat(samples.findByJourneyIdOrderByRoundNo(journeyId)).extracting(JourneySample::getRoundNo)
            .containsExactly(1, 2, 3);
        assertThat(modems.findByJourneyIdOrderByModemIndex(journeyId)).extracting(JourneyModem::getModemIndex)
            .containsExactly(0, 1);
        assertThat(modemSamples.findByJourneyIdOrderByRoundNoAscModemIndexAsc(journeyId))
            .extracting(JourneyModemSample::getRoundNo, JourneyModemSample::getModemIndex)
            .containsExactly(
                tuple(1, 0), tuple(1, 1),
                tuple(2, 0), tuple(2, 1));
    }

    @Test
    void theSpanIsTheEarliestAndTheLatestSampleTimeOfTheJourneyAlone() {
        Long journeyId = newJourney();
        Long otherId = journeys.saveAndFlush(journey(2_000_000L, 1, null)).getId();
        samples.saveAndFlush(sample(journeyId, 2));
        samples.saveAndFlush(sample(journeyId, 1));
        samples.saveAndFlush(sample(journeyId, 3));
        samples.saveAndFlush(sample(otherId, 9));
        entityManager.clear();

        SampleSpan span = samples.spanOf(journeyId);

        assertThat(span.earliest()).isEqualTo(Instant.parse("2024-03-31T23:30:30Z"));
        assertThat(span.latest()).isEqualTo(Instant.parse("2024-03-31T23:31:30Z"));
    }

    @Test
    void theSpanOfAJourneyWithNoSampleIsBothNull() {
        SampleSpan span = samples.spanOf(newJourney());

        assertThat(span.earliest()).isNull();
        assertThat(span.latest()).isNull();
    }

    @Test
    void aModemSampleWithoutItsSampleOrWithoutItsModemIsRefused() {
        Long journeyId = newJourney();
        samples.saveAndFlush(sample(journeyId, 1));
        modems.saveAndFlush(modem(journeyId, 0));

        assertThatThrownBy(() -> modemSamples.saveAndFlush(modemSample(journeyId, 2, 0)))
            .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();
        assertThatThrownBy(() -> modemSamples.saveAndFlush(modemSample(journeyId, 1, 5)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheJourneyTakesItsSamplesModemsAndModemSamplesAndLeavesTheDevice() {
        Long deviceId = devices.saveAndFlush(device("Testbook1,1")).getId();
        Long journeyId = journeys.saveAndFlush(journey(1_000_000L, 1, deviceId)).getId();
        samples.saveAndFlush(sample(journeyId, 1));
        modems.saveAndFlush(modem(journeyId, 0));
        modemSamples.saveAndFlush(modemSample(journeyId, 1, 0));
        entityManager.clear();

        journeys.deleteAllByIdInBatch(List.of(journeyId));
        entityManager.clear();

        assertThat(samples.count()).isZero();
        assertThat(modems.count()).isZero();
        assertThat(modemSamples.count()).isZero();
        assertThat(devices.findById(deviceId)).isPresent();
    }

    @Test
    void theStatsOfEveryJourneyCountAndSpanItsSamplesAndLeaveOutAJourneyWithNone() {
        Long three = journeys.saveAndFlush(journey(2_000_000L, 1, null)).getId();
        Long one = journeys.saveAndFlush(journey(3_000_000L, 1, null)).getId();
        journeys.saveAndFlush(journey(4_000_000L, 1, null));
        samples.saveAllAndFlush(List.of(sample(three, 1), sample(three, 2), sample(three, 3), sample(one, 1)));
        entityManager.clear();

        List<SampleStats> stats = samples.statsOfEveryJourney();

        assertThat(stats).containsExactlyInAnyOrder(
            new SampleStats(three, 3, Instant.parse("2024-03-31T23:30:30Z"), Instant.parse("2024-03-31T23:31:30Z")),
            new SampleStats(one, 1, Instant.parse("2024-03-31T23:30:30Z"), Instant.parse("2024-03-31T23:30:30Z")));
    }
}
