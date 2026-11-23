package gregochr.cinefilm.journey;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.TestData.NOW;
import static gregochr.cinefilm.journey.JourneyFileSamples.JSON;
import static gregochr.cinefilm.journey.JourneyFileSamples.example;
import static gregochr.cinefilm.journey.JourneyFileSamples.firstSample;
import static gregochr.cinefilm.journey.JourneyFileSamples.samples;
import static gregochr.cinefilm.journey.JourneyFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.journey.JourneyImportService.Imported;
import gregochr.cinefilm.journey.JourneyImportService.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class JourneyImportServiceTest {

    private static final long RUN = 1_790_000_000_000L;
    private static final Duration MARGIN = Duration.ofMinutes(2);
    private static final PageRequest TWO_HUNDRED = PageRequest.of(0, 200);

    @Mock
    private JourneyRepository journeys;
    @Mock
    private DeviceRepository devices;
    @Mock
    private JourneySampleRepository samples;
    @Mock
    private JourneyModemRepository modems;
    @Mock
    private JourneyModemSampleRepository modemSamples;
    @Mock
    private JourneyPlaybackRepository links;
    @Mock
    private PlaybackHealthRepository playbacks;

    @Captor
    private ArgumentCaptor<Iterable<Journey>> journeysSaved;
    @Captor
    private ArgumentCaptor<Iterable<JourneySample>> samplesSaved;
    @Captor
    private ArgumentCaptor<Iterable<JourneyModem>> modemsSaved;
    @Captor
    private ArgumentCaptor<Iterable<JourneyModemSample>> modemSamplesSaved;
    @Captor
    private ArgumentCaptor<Iterable<JourneyPlayback>> linksSaved;
    @Captor
    private ArgumentCaptor<Device> deviceSaved;

    private JourneyImportService service;

    @BeforeEach
    void create() {
        service = new JourneyImportService(journeys, devices, samples, modems, modemSamples, links, playbacks, JSON,
            CLOCK);
    }

    /** Journeys get the ids 100, 101 and so on when they are saved, as the database would give them. */
    private void journeysGetIds() {
        doAnswer(call -> {
            List<Journey> saved = new ArrayList<>();
            call.<Iterable<Journey>>getArgument(0).forEach(saved::add);
            for (int i = 0; i < saved.size(); i++) {
                saved.get(i).setId(100L + i);
            }
            return saved;
        }).when(journeys).saveAllAndFlush(anyList());
    }

    private void deviceExists(Long id) {
        Device device = JourneyTestData.device("Testbook1,1");
        device.setId(id);
        when(devices.findByModelAndCpuAndCoresAndThreadsAndMemoryBytes("Testbook1,1", "Test Chip One", 8, 8,
            17_179_869_184L)).thenReturn(Optional.of(device));
    }

    private static ObjectNode unit(ObjectNode tree, int round, String unit) {
        ObjectNode sample = (ObjectNode) samples(tree).get(round - 1);
        if (unit == null) {
            sample.remove("trainUnit");
        } else {
            sample.put("trainUnit", unit);
        }
        return tree;
    }

    private static List<JourneySample> inJourney(Iterable<JourneySample> all, long journeyId) {
        List<JourneySample> found = new ArrayList<>();
        all.forEach(s -> {
            if (s.getJourneyId() == journeyId) {
                found.add(s);
            }
        });
        return found;
    }

    private Result store(ObjectNode tree) {
        return service.importFile(text(tree));
    }

    @Test
    void oneUnitGivesOneJourneyWithTheFilesFactsAndTheClocksImportTime() {
        journeysGetIds();
        deviceExists(7L);
        ObjectNode tree = unit(unit(example(), 5, "Unit-001"), 6, "Unit-001");

        Result result = store(tree);

        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).singleElement().satisfies(journey -> {
            assertThat(journey.getRunStartedMs()).isEqualTo(RUN);
            assertThat(journey.getSegment()).isZero();
            assertThat(journey.getDeviceId()).isEqualTo(7L);
            assertThat(journey.getOsVersion()).isEqualTo("TestOS 1.2");
            assertThat(journey.getTravelClass()).isEqualTo("standard");
            assertThat(journey.getTrainUnit()).isEqualTo("Unit-001");
            assertThat(journey.getSourceFile()).isEqualTo("test-feed-2026-09-21-1000.jsonl");
            assertThat(journey.getLoggerVersion()).isEqualTo(3);
            assertThat(journey.getImportedAt()).isEqualTo(NOW);
        });
        verify(samples).saveAllAndFlush(samplesSaved.capture());
        assertThat(samplesSaved.getValue()).extracting(JourneySample::getJourneyId).containsOnly(100L).hasSize(6);
        assertThat(result.journeys()).extracting(Imported::id, Imported::samples).containsExactly(tuple(100L, 6));
    }

    @Test
    void twoUnitsGiveTwoSegmentsAndTheRoundsWithoutAUnitStayInTheJourneyTheyFallIn() {
        journeysGetIds();
        deviceExists(7L);

        Result result = store(example());

        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).extracting(Journey::getSegment, Journey::getTrainUnit,
            Journey::getRunStartedMs).containsExactly(tuple(0, "Unit-001", RUN), tuple(1, "Unit-002", RUN));
        verify(samples).saveAllAndFlush(samplesSaved.capture());
        assertThat(inJourney(samplesSaved.getValue(), 100)).extracting(JourneySample::getRoundNo)
            .containsExactly(1, 2, 3, 4);
        assertThat(inJourney(samplesSaved.getValue(), 101)).extracting(JourneySample::getRoundNo)
            .containsExactly(5, 6);
        assertThat(result.journeys()).extracting(Imported::segment, Imported::trainUnit)
            .containsExactly(tuple(0, "Unit-001"), tuple(1, "Unit-002"));
    }

    @Test
    void aJourneyWithNoUnitYetTakesTheFirstOneGivenAndTheNextDifferentOneBeginsTheNextSegment() {
        journeysGetIds();
        ObjectNode tree = example();
        tree.remove("device");
        unit(tree, 1, null);
        unit(tree, 2, null);
        unit(tree, 3, "Unit-009");

        store(tree);

        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).extracting(Journey::getSegment, Journey::getTrainUnit)
            .containsExactly(tuple(0, "Unit-009"), tuple(1, "Unit-002"));
        verify(samples).saveAllAndFlush(samplesSaved.capture());
        assertThat(inJourney(samplesSaved.getValue(), 100)).extracting(JourneySample::getRoundNo)
            .containsExactly(1, 2, 3, 4);
    }

    @Test
    void aFileWithNoUnitAtAllIsOneJourneyWithNoUnit() {
        journeysGetIds();
        deviceExists(7L);
        ObjectNode tree = example();
        for (int round = 1; round <= 6; round++) {
            unit(tree, round, null);
        }

        Result result = store(tree);

        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).singleElement().extracting(Journey::getTrainUnit).isNull();
        assertThat(result.journeys()).extracting(Imported::trainUnit, Imported::samples)
            .containsExactly(tuple(null, 6));
    }

    @Test
    void aSamplesColumnsAreStoredWithItsTimesAsInstants() {
        journeysGetIds();
        deviceExists(7L);

        store(example());

        verify(samples).saveAllAndFlush(samplesSaved.capture());
        JourneySample first = inJourney(samplesSaved.getValue(), 100).getFirst();
        assertThat(first.getSampledAt()).isEqualTo(Instant.ofEpochMilli(RUN + 30_000));
        assertThat(first.getFeedAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_029L));
        assertThat(first).hasNoNullFieldsOrPropertiesExcept("stored");
        assertThat(first.getLatitude()).isEqualTo(12.5);
        assertThat(first.getWifiRate()).isEqualTo(286.5);
        assertThat(first.getPowerSource()).isEqualTo("battery");
        JourneySample failed = inJourney(samplesSaved.getValue(), 100).get(2);
        assertThat(failed.getSampledAt()).isEqualTo(Instant.ofEpochMilli(RUN + 90_000));
        assertThat(failed.getLatitude()).isNull();
    }

    @Test
    void modemsAreTheIndexesASegmentsSamplesNameAndTheirStatesAreStoredWithNullsAsGiven() {
        journeysGetIds();
        deviceExists(7L);

        store(example());

        verify(modems).saveAllAndFlush(modemsSaved.capture());
        assertThat(modemsSaved.getValue()).extracting(JourneyModem::getJourneyId, JourneyModem::getModemIndex,
            JourneyModem::getDeviceType, JourneyModem::getDeviceSubtype).containsExactly(
                tuple(100L, 100, "TestModemType", "TestSubtype"), tuple(100L, 101, "TestModemType", "TestSubtype"),
                tuple(101L, 100, "TestModemType", "TestSubtype"));
        verify(modemSamples).saveAllAndFlush(modemSamplesSaved.capture());
        assertThat(modemSamplesSaved.getValue()).extracting(JourneyModemSample::getJourneyId,
            JourneyModemSample::getRoundNo, JourneyModemSample::getModemIndex).containsExactly(
                tuple(100L, 1, 100), tuple(100L, 1, 101), tuple(100L, 2, 100), tuple(100L, 4, 101),
                tuple(101L, 5, 100));
        JourneyModemSample full = modemSamplesSaved.getValue().iterator().next();
        assertThat(full).hasNoNullFieldsOrPropertiesExcept("stored");
        assertThat(full.getCellId()).isEqualTo(123_456L);
        JourneyModemSample silent = List.copyOf(toList(modemSamplesSaved.getValue())).get(1);
        assertThat(silent.getDeviceState()).isEqualTo("down");
        assertThat(silent.getRssi()).isNull();
        assertThat(silent.getTechnology()).isNull();
        assertThat(silent.getOperatorId()).isNull();
        assertThat(silent.getCellId()).isNull();
    }

    private static <T> List<T> toList(Iterable<T> all) {
        List<T> list = new ArrayList<>();
        all.forEach(list::add);
        return list;
    }

    @ParameterizedTest
    @ValueSource(strings = {"deviceType", "deviceSubtype"})
    void aLaterSampleGivingAModemAnotherTypeOrSubtypeRefusesTheWholeFileAndStoresNothing(String field) {
        ObjectNode tree = example();
        ((ObjectNode) samples(tree).get(1).get("modems").get(0)).put(field, "Other");

        assertThatThrownBy(() -> store(tree)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");

        verifyNoInteractions(journeys, devices, samples, modems, modemSamples, links, playbacks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deviceType", "deviceSubtype"})
    void aMissingValueInALaterSampleIsNotGivenAndTheFirstValueIsKept(String field) {
        journeysGetIds();
        deviceExists(7L);
        ObjectNode tree = example();
        ((ObjectNode) samples(tree).get(1).get("modems").get(0)).putNull(field);

        store(tree);

        verify(modems).saveAllAndFlush(modemsSaved.capture());
        assertThat(modemsSaved.getValue()).filteredOn(m -> m.getJourneyId() == 100L && m.getModemIndex() == 100)
            .extracting(JourneyModem::getDeviceType, JourneyModem::getDeviceSubtype)
            .containsExactly(tuple("TestModemType", "TestSubtype"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deviceType", "deviceSubtype"})
    void aValueGivenAfterAMissingOneIsTaken(String field) {
        journeysGetIds();
        deviceExists(7L);
        ObjectNode tree = example();
        ((ObjectNode) samples(tree).get(0).get("modems").get(0)).putNull(field);

        store(tree);

        verify(modems).saveAllAndFlush(modemsSaved.capture());
        assertThat(modemsSaved.getValue()).filteredOn(m -> m.getJourneyId() == 100L && m.getModemIndex() == 100)
            .extracting(JourneyModem::getDeviceType, JourneyModem::getDeviceSubtype)
            .containsExactly(tuple("TestModemType", "TestSubtype"));
    }

    @Test
    void aModemNeverGivenATypeIsStoredWithNone() {
        journeysGetIds();
        deviceExists(7L);
        ObjectNode tree = example();
        for (int round = 0; round < 6; round++) {
            if (samples(tree).get(round).has("modems")) {
                for (var modem : samples(tree).get(round).get("modems")) {
                    ((ObjectNode) modem).remove("deviceType");
                }
            }
        }

        store(tree);

        verify(modems).saveAllAndFlush(modemsSaved.capture());
        assertThat(modemsSaved.getValue()).extracting(JourneyModem::getDeviceType).containsOnlyNulls();
    }

    @Test
    void aModemOfAnotherSegmentMayHaveAnotherType() {
        journeysGetIds();
        deviceExists(7L);
        ObjectNode tree = example();
        ((ObjectNode) samples(tree).get(4).get("modems").get(0)).put("deviceType", "Other");

        store(tree);

        verify(modems).saveAllAndFlush(modemsSaved.capture());
        assertThat(modemsSaved.getValue()).extracting(JourneyModem::getJourneyId, JourneyModem::getDeviceType)
            .contains(tuple(101L, "Other"), tuple(100L, "TestModemType"));
    }

    @Test
    void aDeviceAlreadyStoredIsFoundByItsFiveColumnsAndNotStoredAgain() {
        journeysGetIds();
        deviceExists(7L);

        store(example());

        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).extracting(Journey::getDeviceId).containsExactly(7L, 7L);
        verify(devices).findByModelAndCpuAndCoresAndThreadsAndMemoryBytes("Testbook1,1", "Test Chip One", 8, 8,
            17_179_869_184L);
        verifyNoMoreInteractions(devices);
    }

    @Test
    void aDeviceNotYetStoredIsMadeFromTheFilesFiveValues() {
        journeysGetIds();
        when(devices.findByModelAndCpuAndCoresAndThreadsAndMemoryBytes("Testbook1,1", "Test Chip One", 8, 8,
            17_179_869_184L)).thenReturn(Optional.empty());
        when(devices.save(deviceSaved.capture())).thenAnswer(call -> {
            Device made = call.getArgument(0);
            made.setId(9L);
            return made;
        });

        store(example());

        assertThat(deviceSaved.getValue()).usingRecursiveComparison().isEqualTo(deviceWith(9L));
        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).extracting(Journey::getDeviceId).containsExactly(9L, 9L);
    }

    private static Device deviceWith(Long id) {
        Device device = JourneyTestData.device("Testbook1,1");
        device.setId(id);
        return device;
    }

    @Test
    void aFileWithNoDeviceGivesTheJourneysNoDeviceAndTouchesNone() {
        journeysGetIds();
        ObjectNode tree = example();
        tree.remove("device");

        store(tree);

        verify(journeys).saveAllAndFlush(journeysSaved.capture());
        assertThat(journeysSaved.getValue()).extracting(Journey::getDeviceId).containsOnlyNulls();
        verifyNoInteractions(devices);
    }

    @Test
    void theLinkQueryIsAskedForEachJourneyWithTwoMinutesEitherSideOfItsFirstAndLastSampleAndTheLinksAreStored() {
        journeysGetIds();
        deviceExists(7L);
        Instant firstFrom = Instant.ofEpochMilli(RUN + 30_000).minus(MARGIN);
        Instant firstTo = Instant.ofEpochMilli(RUN + 120_000).plus(MARGIN);
        Instant secondFrom = Instant.ofEpochMilli(RUN + 150_000).minus(MARGIN);
        Instant secondTo = Instant.ofEpochMilli(RUN + 180_000).plus(MARGIN);
        when(playbacks.idsOverlapping(firstFrom, firstTo, TWO_HUNDRED)).thenReturn(List.of(5L, 6L));
        when(playbacks.idsOverlapping(secondFrom, secondTo, TWO_HUNDRED)).thenReturn(List.of(6L));

        Result result = store(example());

        verify(links).saveAllAndFlush(linksSaved.capture());
        assertThat(linksSaved.getValue()).extracting(JourneyPlayback::getJourneyId,
            JourneyPlayback::getPlaybackHealthId).containsExactly(tuple(100L, 5L), tuple(100L, 6L), tuple(101L, 6L));
        assertThat(result.journeys()).extracting(Imported::linked).containsExactly(2, 1);
    }

    @Test
    void theAnswerHasEachJourneysIdSegmentUnitSampleCountFirstAndLastTimeAndLinkCount() {
        journeysGetIds();
        deviceExists(7L);
        when(playbacks.idsOverlapping(Instant.ofEpochMilli(RUN + 30_000).minus(MARGIN),
            Instant.ofEpochMilli(RUN + 120_000).plus(MARGIN), TWO_HUNDRED)).thenReturn(List.of());
        when(playbacks.idsOverlapping(Instant.ofEpochMilli(RUN + 150_000).minus(MARGIN),
            Instant.ofEpochMilli(RUN + 180_000).plus(MARGIN), TWO_HUNDRED)).thenReturn(List.of(8L));

        Result result = store(example());

        assertThat(result.journeys()).containsExactly(
            new Imported(100L, 0, "Unit-001", 4, Instant.ofEpochMilli(RUN + 30_000),
                Instant.ofEpochMilli(RUN + 120_000), 0),
            new Imported(101L, 1, "Unit-002", 2, Instant.ofEpochMilli(RUN + 150_000),
                Instant.ofEpochMilli(RUN + 180_000), 1));
    }

    @Test
    void aRunAlreadyStoredIsAConflictAndNothingIsStored() {
        when(journeys.existsByRunStartedMs(RUN)).thenReturn(true);

        assertThatThrownBy(() -> store(example())).isExactlyInstanceOf(ConflictException.class)
            .hasMessage("Already imported");

        verify(journeys).existsByRunStartedMs(RUN);
        verifyNoMoreInteractions(journeys);
        verifyNoInteractions(devices, samples, modems, modemSamples, links, playbacks);
    }

    @Test
    void aNullBodyIsA400AndNothingIsAskedOrStored() {
        assertThatThrownBy(() -> service.importFile(null)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");

        verifyNoInteractions(journeys, devices, samples, modems, modemSamples, links, playbacks);
    }

    @Test
    void aBodyThatDoesNotParseOrKeepTheRulesIsA400AndNothingIsAskedOrStored() {
        ObjectNode outOfRange = example();
        firstSample(outOfRange).put("latitude", 91.0);
        ObjectNode unknownName = example();
        unknownName.put("macAddress", "x");

        assertThatThrownBy(() -> service.importFile("{")).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");
        assertThatThrownBy(() -> store(outOfRange)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");
        assertThatThrownBy(() -> store(unknownName)).isExactlyInstanceOf(BadRequestException.class)
            .hasMessage("Invalid journey file");

        verifyNoInteractions(journeys, devices, samples, modems, modemSamples, links, playbacks);
    }
}
