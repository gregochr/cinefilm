package gregochr.cinefilm.journey;

import static gregochr.cinefilm.journey.JourneyTestData.device;
import static gregochr.cinefilm.journey.JourneyTestData.fullJourney;
import static gregochr.cinefilm.journey.JourneyTestData.fullTrainRun;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.journey.JourneyService.DeviceView;
import gregochr.cinefilm.journey.JourneyService.Entry;
import gregochr.cinefilm.journey.JourneyService.TrainView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JourneyServiceTest {

    @Mock
    private JourneyRepository journeys;
    @Mock
    private JourneySampleRepository samples;
    @Mock
    private JourneyPlaybackRepository links;
    @Mock
    private DeviceRepository devices;
    @Mock
    private TrainRunRepository runs;
    @Mock
    private JourneyTrainService trains;

    private JourneyService service;

    @BeforeEach
    void setUp() {
        service = new JourneyService(journeys, samples, links, devices, runs, trains);
    }

    private static Journey withId(Journey journey, long id) {
        journey.setId(id);
        return journey;
    }

    @Test
    void aJourneyWithADeviceSamplesLinksAndATrainReadsWithEveryValue() {
        Journey full = withId(fullJourney(5L, 7L), 11L);
        Device laptop = device("Testbook9,9");
        laptop.setId(5L);
        TrainRun run = fullTrainRun();
        run.setId(7L);
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc()).thenReturn(List.of(full));
        when(samples.statsOfEveryJourney()).thenReturn(List.of(new SampleStats(11L, 3,
            Instant.parse("2030-01-15T10:00:00Z"), Instant.parse("2030-01-15T10:01:30Z"))));
        when(links.countsOfEveryJourney()).thenReturn(List.of(new LinkCount(11L, 2)));
        when(devices.findAllById(List.of(5L))).thenReturn(List.of(laptop));
        when(runs.findAllById(List.of(7L))).thenReturn(List.of(run));

        List<Entry> entries = service.list().journeys();

        assertThat(entries).containsExactly(new Entry(11L, 1_000_000L, 2, "Unit-000", "first", "TestOS 1.2", 3,
            "test-log.jsonl", Instant.parse("2024-03-31T23:30:00Z"),
            new DeviceView("Testbook9,9", "Test Chip One", 8, 8, 17_179_869_184L), 3,
            Instant.parse("2030-01-15T10:00:00Z"), Instant.parse("2030-01-15T10:01:30Z"), 2,
            new TrainView("X12345", "2030-01-15", "9Z99")));
    }

    @Test
    void aJourneyWithNoDeviceSamplesLinksOrTrainReadsWithTheNullsAndTheZeros() {
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc())
            .thenReturn(List.of(withId(journey(1_000L, 1, null), 12L)));

        List<Entry> entries = service.list().journeys();

        assertThat(entries).containsExactly(new Entry(12L, 1_000L, 1, null, null, null, 3, null,
            Instant.parse("2024-03-31T23:30:00Z"), null, 0, null, null, 0, null));
    }

    @Test
    void theListKeepsTheFindersOrderAndGivesASharedDeviceAndRunToEveryEntryThatHasThem() {
        Journey newest = withId(journey(3_000L, 0, 5L), 21L);
        Journey olderFirst = withId(journey(2_000L, 0, 5L), 22L);
        Journey olderSecond = withId(journey(2_000L, 1, 5L), 23L);
        newest.setTrainRunId(7L);
        olderFirst.setTrainRunId(7L);
        when(journeys.findAllByOrderByRunStartedMsDescSegmentAsc())
            .thenReturn(List.of(newest, olderFirst, olderSecond));
        Device laptop = device("Testbook9,9");
        laptop.setId(5L);
        TrainRun run = fullTrainRun();
        run.setId(7L);
        when(devices.findAllById(List.of(5L))).thenReturn(List.of(laptop));
        when(runs.findAllById(List.of(7L))).thenReturn(List.of(run));

        List<Entry> entries = service.list().journeys();

        assertThat(entries).extracting(Entry::id).containsExactly(21L, 22L, 23L);
        assertThat(entries).extracting(e -> e.device().model()).containsExactly("Testbook9,9", "Testbook9,9",
            "Testbook9,9");
        assertThat(entries).extracting(e -> e.train() == null ? null : e.train().uid())
            .containsExactly("X12345", "X12345", null);
    }

    @Test
    void aDeleteOfAnUnknownJourneyIsANotFoundWithItsMessage() {
        when(journeys.findLockedById(33L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(33L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such journey");
    }
}
