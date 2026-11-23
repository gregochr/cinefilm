package gregochr.cinefilm.journey;

import static gregochr.cinefilm.TestData.CLOCK;
import static gregochr.cinefilm.journey.JourneyTestData.call;
import static gregochr.cinefilm.journey.JourneyTestData.journey;
import static gregochr.cinefilm.journey.JourneyTestData.trainRun;
import static gregochr.cinefilm.journey.TrainFileSamples.JSON;
import static gregochr.cinefilm.journey.TrainFileSamples.calls;
import static gregochr.cinefilm.journey.TrainFileSamples.example;
import static gregochr.cinefilm.journey.TrainFileSamples.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.journey.JourneyTrainService.Stored;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class JourneyTrainServiceTest {

    private static final LocalDate DAY = LocalDate.of(2030, 3, 4);
    private static final Instant FIRST_SAMPLE = Instant.parse("2030-03-04T22:10:00Z");
    private static final Instant LAST_SAMPLE = Instant.parse("2030-03-05T00:30:00Z");

    @Mock
    private JourneyRepository journeys;
    @Mock
    private JourneySampleRepository samples;
    @Mock
    private TrainRunRepository runs;
    @Mock
    private TrainRunCallRepository calls;

    @Captor
    private ArgumentCaptor<TrainRun> runSaved;
    @Captor
    private ArgumentCaptor<Journey> journeySaved;
    @Captor
    private ArgumentCaptor<Iterable<TrainRunCall>> callsSaved;

    private JourneyTrainService service;
    private Journey journey;

    @BeforeEach
    void setUp() {
        service = new JourneyTrainService(journeys, samples, runs, calls, JSON, CLOCK);
        journey = journey(1_000L, 1, null);
        journey.setId(5L);
    }

    private void locked() {
        when(journeys.findLockedById(5L)).thenReturn(Optional.of(journey));
    }

    private void sampled(Instant earliest, Instant latest) {
        locked();
        when(samples.spanOf(5L)).thenReturn(new SampleSpan(earliest, latest));
    }

    private void noRun() {
        sampled(FIRST_SAMPLE, LAST_SAMPLE);
        when(runs.findByTimetableUidAndRunDate("X12345", DAY)).thenReturn(Optional.empty());
        savesRun();
    }

    private void savesRun() {
        when(runs.save(any(TrainRun.class))).thenAnswer(invocation -> {
            TrainRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(77L);
            }
            return run;
        });
    }

    private static String body() {
        return text(example());
    }

    private static String bodyWith(java.util.function.Consumer<ObjectNode> edit) {
        ObjectNode root = example();
        edit.accept(root);
        return text(root);
    }

    private TrainRun storedRun() {
        TrainRun run = trainRun("X12345", DAY);
        run.setId(77L);
        run.setHeadcode("1A11");
        run.setOperator("Old Rail");
        run.setSource("https://example.invalid/old");
        run.setImportedAt(Instant.parse("2030-03-01T00:00:00Z"));
        return run;
    }

    private void storedWith(String first, String last) {
        sampled(FIRST_SAMPLE, LAST_SAMPLE);
        TrainRunCall start = call(77L, 0);
        start.setLocation(first);
        TrainRunCall end = call(77L, 7);
        end.setLocation(last);
        when(runs.findByTimetableUidAndRunDate("X12345", DAY)).thenReturn(Optional.of(storedRun()));
        when(calls.findByTrainRunIdOrderBySeq(77L)).thenReturn(List.of(start, end));
    }

    @Test
    void aNewRunIsStoredWithItsCallsAndTheJourneyPointsAtIt() {
        noRun();
        Stored answer = service.give(5L, body());

        assertThat(answer).isEqualTo(new Stored("X12345", "2030-03-04", "9Z99", 8));
        verify(runs).findByTimetableUidAndRunDate("X12345", DAY);
        verify(runs).save(runSaved.capture());
        TrainRun run = runSaved.getValue();
        assertThat(run.getTimetableUid()).isEqualTo("X12345");
        assertThat(run.getRunDate()).isEqualTo(LocalDate.of(2030, 3, 4));
        assertThat(run.getHeadcode()).isEqualTo("9Z99");
        assertThat(run.getOperator()).isEqualTo("Example Rail");
        assertThat(run.getSource()).isEqualTo("https://example.invalid/run");
        assertThat(run.getImportedAt()).isEqualTo(Instant.parse("2024-03-31T23:30:00Z"));
        verify(calls).saveAll(callsSaved.capture());
        assertThat(callsSaved.getValue()).extracting(TrainRunCall::getTrainRunId, TrainRunCall::getSeq,
                TrainRunCall::getLocation, TrainRunCall::getPlannedArrival, TrainRunCall::getPlannedDeparture,
                TrainRunCall::getPlannedPass, TrainRunCall::getActualArrival, TrainRunCall::getActualDeparture,
                TrainRunCall::getActualPass)
            .containsExactly(
                tuple(77L, 0, "Alpha Junction", null, at("2030-03-04T22:00:00Z"), null, null,
                    at("2030-03-04T22:01:30Z"), null),
                tuple(77L, 1, "Beta Parkway", at("2030-03-04T22:30:00Z"), at("2030-03-04T22:32:00Z"), null,
                    at("2030-03-04T22:31:30Z"), at("2030-03-04T22:34:00Z"), null),
                tuple(77L, 2, "Gamma Loop", null, null, at("2030-03-04T22:50:30Z"), null, null,
                    at("2030-03-04T22:53:00Z")),
                tuple(77L, 3, "Delta Cross", null, null, at("2030-03-04T23:10:00Z"), at("2030-03-04T23:12:00Z"),
                    at("2030-03-04T23:13:30Z"), null),
                tuple(77L, 4, "Epsilon Green", at("2030-03-04T23:40:00Z"), at("2030-03-04T23:41:00Z"), null, null,
                    null, null),
                tuple(77L, 5, "Zeta Sidings", null, null, at("2030-03-04T23:59:30Z"), null, null,
                    at("2030-03-05T00:02:00Z")),
                tuple(77L, 6, "Eta Works", null, null, at("2030-03-05T00:20:00Z"), null, null,
                    at("2030-03-05T00:22:30Z")),
                tuple(77L, 7, "Theta Central", at("2030-03-05T00:45:00Z"), null, null, at("2030-03-05T00:47:30Z"),
                    null, null));
        verify(journeys).save(journeySaved.capture());
        assertThat(journeySaved.getValue().getTrainRunId()).isEqualTo(77L);
        verifyNoMoreInteractions(runs, calls);
    }

    private static Instant at(String text) {
        return Instant.parse(text);
    }

    @Test
    void aStoredRunIsReplacedByTheFileWithItsHeadcodeOperatorSourceAndTimeAndItsCallsReplaced() {
        storedWith("Alpha Junction", "Theta Central");
        savesRun();

        Stored answer = service.give(5L, bodyWith(root -> root.put("headcode", "9Z98")));

        assertThat(answer).isEqualTo(new Stored("X12345", "2030-03-04", "9Z98", 8));
        verify(runs).save(runSaved.capture());
        verify(calls).deleteAllOfRun(77L);
        verify(calls).saveAll(callsSaved.capture());
        verify(journeys).save(journeySaved.capture());
        TrainRun run = runSaved.getValue();
        assertThat(run.getId()).isEqualTo(77L);
        assertThat(run.getHeadcode()).isEqualTo("9Z98");
        assertThat(run.getOperator()).isEqualTo("Example Rail");
        assertThat(run.getSource()).isEqualTo("https://example.invalid/run");
        assertThat(run.getImportedAt()).isEqualTo(Instant.parse("2024-03-31T23:30:00Z"));
        assertThat(callsSaved.getValue()).hasSize(8);
        assertThat(journeySaved.getValue().getTrainRunId()).isEqualTo(77L);
    }

    @Test
    void aFileThatIsSilentOnTheOperatorAndTheSourceLeavesThemAsStored() {
        storedWith("Alpha Junction", "Theta Central");
        savesRun();

        service.give(5L, bodyWith(root -> root.remove(List.of("operator", "source"))));

        verify(runs).save(runSaved.capture());
        assertThat(runSaved.getValue().getHeadcode()).isEqualTo("9Z99");
        assertThat(runSaved.getValue().getOperator()).isEqualTo("Old Rail");
        assertThat(runSaved.getValue().getSource()).isEqualTo("https://example.invalid/old");
        assertThat(runSaved.getValue().getImportedAt()).isEqualTo(Instant.parse("2024-03-31T23:30:00Z"));
    }

    @Test
    void aStoredRunWithAnotherFirstPlaceIsAConflictAndNothingIsSaved() {
        storedWith("Omega Halt", "Theta Central");

        assertThatThrownBy(() -> service.give(5L, body())).isInstanceOf(ConflictException.class)
            .hasMessage("This train is stored with other end points");

        verify(runs).findByTimetableUidAndRunDate("X12345", DAY);
        verify(calls).findByTrainRunIdOrderBySeq(77L);
        verifyNoMoreInteractions(runs, calls);
    }

    @Test
    void aStoredRunWithAnotherLastPlaceIsAConflictAndNothingIsSaved() {
        storedWith("Alpha Junction", "Omega Halt");

        assertThatThrownBy(() -> service.give(5L, body())).isInstanceOf(ConflictException.class)
            .hasMessage("This train is stored with other end points");

        verify(runs).findByTimetableUidAndRunDate("X12345", DAY);
        verifyNoMoreInteractions(runs);
        verify(calls).findByTrainRunIdOrderBySeq(77L);
        verifyNoMoreInteractions(calls);
    }

    @ParameterizedTest
    @CsvSource({
        "2030-03-05T01:17:30Z,2030-03-05T01:20:00Z",
        "2030-03-04T21:00:00Z,2030-03-04T21:30:00Z"})
    void aJourneyExactlyThirtyMinutesFromTheTrainIsAccepted(Instant earliest, Instant latest) {
        sampled(earliest, latest);
        when(runs.findByTimetableUidAndRunDate("X12345", DAY)).thenReturn(Optional.empty());
        savesRun();

        assertThat(service.give(5L, body()).calls()).isEqualTo(8);
    }

    @ParameterizedTest
    @CsvSource({
        "2030-03-05T01:17:30.001Z,2030-03-05T01:20:00Z",
        "2030-03-04T21:00:00Z,2030-03-04T21:29:59.999Z"})
    void aJourneyAMillisecondBeyondTheMarginIsRefused(Instant earliest, Instant latest) {
        sampled(earliest, latest);

        assertThatThrownBy(() -> service.give(5L, body())).isInstanceOf(BadRequestException.class)
            .hasMessage("The train did not run during this journey");
    }

    @Test
    void aJourneyWithNoSampleIsRefusedTheSameWay() {
        sampled(null, null);

        assertThatThrownBy(() -> service.give(5L, body())).isInstanceOf(BadRequestException.class)
            .hasMessage("The train did not run during this journey");
    }

    @Test
    void aJourneyMovedFromOneRunToAnotherSweepsTheFirst() {
        journey.setTrainRunId(66L);
        noRun();

        service.give(5L, body());

        verify(journeys).save(journeySaved.capture());
        verify(runs).findLockedById(66L);
        verify(runs).deleteIfUnused(66L);
        assertThat(journeySaved.getValue().getTrainRunId()).isEqualTo(77L);
    }

    @Test
    void aJourneyGivenTheRunItAlreadyHasSweepsNothing() {
        journey.setTrainRunId(77L);
        storedWith("Alpha Junction", "Theta Central");
        savesRun();

        service.give(5L, body());

        verify(runs).findByTimetableUidAndRunDate("X12345", DAY);
        verify(runs).save(runSaved.capture());
        verifyNoMoreInteractions(runs);
    }

    @Test
    void takingTheTrainAwaySavesTheJourneyWithoutItAndSweepsItsRun() {
        journey.setTrainRunId(66L);
        locked();

        service.takeAway(5L);

        verify(journeys).save(journeySaved.capture());
        verify(runs).findLockedById(66L);
        verify(runs).deleteIfUnused(66L);
        assertThat(journeySaved.getValue().getTrainRunId()).isNull();
    }

    @Test
    void aJourneyWithoutATrainIsLeftAsItIs() {
        locked();
        service.takeAway(5L);

        verify(journeys).findLockedById(5L);
        verifyNoMoreInteractions(journeys);
        verifyNoInteractions(runs);
    }

    @Test
    void sweepLocksTheRunAndDeletesItOnlyThroughTheOneStatement() {
        service.sweep(66L);

        verify(runs).findLockedById(66L);
        verify(runs).deleteIfUnused(66L);
        verifyNoMoreInteractions(runs);
    }

    @Test
    void anUnknownJourneyIsA404ForBothMethods() {
        when(journeys.findLockedById(6L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.give(6L, body())).isInstanceOf(NotFoundException.class)
            .hasMessage("No such journey");
        assertThatThrownBy(() -> service.takeAway(6L)).isInstanceOf(NotFoundException.class)
            .hasMessage("No such journey");
    }

    @Test
    void aBodyThatDoesNotReadIsA400() {
        locked();

        assertThatThrownBy(() -> service.give(5L, "not json")).isInstanceOf(BadRequestException.class)
            .hasMessage("Invalid train file");
    }

    @Test
    void aFileThatBreaksARuleIsA400() {
        locked();

        assertThatThrownBy(() -> service.give(5L, bodyWith(root -> root.remove("uid"))))
            .isInstanceOf(BadRequestException.class).hasMessage("Invalid train file");
    }

    @Test
    void aFileWhoseTimesGoBackwardsIsA400() {
        locked();
        String backwards = bodyWith(root -> ((ObjectNode) calls(root).get(1)).put("plannedArrival", "21:00"));

        assertThatThrownBy(() -> service.give(5L, backwards)).isInstanceOf(BadRequestException.class)
            .hasMessage("Invalid train file");
    }
}
