package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyTestData.device;
import static gregochr.cinefilm.journey.JourneyTestData.fullJourney;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import gregochr.cinefilm.ApiTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Journeys and the devices that logged them: the unique keys, the class check and the finders. */
class JourneyRepositoryTest extends ApiTestBase {

    @Autowired
    private JourneyRepository journeys;

    @Autowired
    private DeviceRepository devices;

    @Autowired
    private EntityManager entityManager;

    @Test
    void aJourneyWithADeviceReadsBackWithEveryColumn() {
        Long deviceId = devices.saveAndFlush(device("Testbook1,1")).getId();
        Long id = journeys.saveAndFlush(fullJourney(deviceId)).getId();
        entityManager.clear();

        Journey loaded = journeys.findById(id).orElseThrow();

        Journey expected = fullJourney(deviceId);
        expected.setId(id);
        assertThat(expected).hasNoNullFieldsOrProperties();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    void aJourneyWithoutADeviceOrOptionalColumnsReadsBackWithNulls() {
        Long id = journeys.saveAndFlush(journey(1_000_000L, 1, null)).getId();
        entityManager.clear();

        Journey loaded = journeys.findById(id).orElseThrow();

        assertThat(loaded.getDeviceId()).isNull();
        assertThat(loaded.getOsVersion()).isNull();
        assertThat(loaded.getTravelClass()).isNull();
        assertThat(loaded.getTrainUnit()).isNull();
        assertThat(loaded.getSourceFile()).isNull();
    }

    @Test
    void theSameRunAndSegmentCannotBeStoredTwiceButAnotherSegmentOfTheRunCan() {
        journeys.saveAndFlush(journey(1_000_000L, 1, null));

        assertThatThrownBy(() -> journeys.saveAndFlush(journey(1_000_000L, 1, null)))
            .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        journeys.saveAndFlush(journey(1_000_000L, 2, null));
        assertThat(journeys.count()).isEqualTo(2);
    }

    @Test
    void existsByRunStartedMsIsTrueOnlyForAStoredRun() {
        journeys.saveAndFlush(journey(1_000_000L, 1, null));

        assertThat(journeys.existsByRunStartedMs(1_000_000L)).isTrue();
        assertThat(journeys.existsByRunStartedMs(2_000_000L)).isFalse();
    }

    @Test
    void theListIsNewestRunFirstAndSegmentsInOrder() {
        journeys.saveAndFlush(journey(1_000_000L, 2, null));
        journeys.saveAndFlush(journey(3_000_000L, 1, null));
        journeys.saveAndFlush(journey(1_000_000L, 1, null));
        journeys.saveAndFlush(journey(3_000_000L, 2, null));

        assertThat(journeys.findAllByOrderByRunStartedMsDescSegmentAsc())
            .extracting(Journey::getRunStartedMs, Journey::getSegment)
            .containsExactly(
                tuple(3_000_000L, 1),
                tuple(3_000_000L, 2),
                tuple(1_000_000L, 1),
                tuple(1_000_000L, 2));
    }

    @Test
    void aTravelClassOtherThanStandardOrFirstIsRefusedAndStandardIsStored() {
        Journey standard = journey(1_000_000L, 1, null);
        standard.setTravelClass("standard");
        journeys.saveAndFlush(standard);
        Journey wrong = journey(2_000_000L, 1, null);
        wrong.setTravelClass("business");

        assertThatThrownBy(() -> journeys.saveAndFlush(wrong)).isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        assertThat(journeys.count()).isEqualTo(1);
    }

    @Test
    void theSameFiveDeviceColumnsCannotBeStoredTwiceAndTheFinderFindsTheKind() {
        Device first = devices.saveAndFlush(device("Testbook1,1"));
        Device other = device("Testbook1,1");
        other.setCores(10);
        devices.saveAndFlush(other);

        assertThatThrownBy(() -> devices.saveAndFlush(device("Testbook1,1")))
            .isInstanceOf(DataIntegrityViolationException.class);
        entityManager.clear();

        assertThat(devices.findByModelAndCpuAndCoresAndThreadsAndMemoryBytes(
            "Testbook1,1", "Test Chip One", 8, 8, 17_179_869_184L).orElseThrow().getId()).isEqualTo(first.getId());
        assertThat(devices.findByModelAndCpuAndCoresAndThreadsAndMemoryBytes(
            "Testbook1,1", "Test Chip One", 8, 8, 1L)).isEmpty();
        assertThat(devices.count()).isEqualTo(2);
    }
}
