package gregochr.cinefilm.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.TrainFile.Call;
import gregochr.cinefilm.journey.TrainTimes.Span;
import gregochr.cinefilm.journey.TrainTimes.Times;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The train's clock: planned and actual times as instants, every threshold to the second, each way. */
class TrainTimesTest {

    private static final String DAY = "2030-03-04";

    /** A call of the file, its six clock times in the file's order (null where not given). */
    private static Call call(String location, String pa, String pd, String pp, String aa, String ad, String ap) {
        return new Call(location, pa, pd, pp, aa, ad, ap);
    }

    /** A call with a planned pass only. */
    private static Call pass(String clock) {
        return call("Pass Point", null, null, clock, null, null, null);
    }

    /** A call that comes after any other planned time of these tests and holds nothing else of interest. */
    private static Call filler() {
        return pass("23:55");
    }

    private static List<Times> run(String date, Call... calls) {
        TrainFile file = new TrainFile("X12345", date, "9Z99", null, null, List.of(calls));
        TrainFileRules.validate(file);
        return TrainTimes.instants(file);
    }

    /** What a call must come to: its location, then six ISO instants (null where none). */
    private static Times times(String location, String pa, String pd, String pp, String aa, String ad, String ap) {
        return new Times(location, instant(pa), instant(pd), instant(pp), instant(aa), instant(ad), instant(ap));
    }

    private static Instant instant(String text) {
        return text == null ? null : Instant.parse(text);
    }

    private static void assertRefused(String date, Call... calls) {
        assertThatThrownBy(() -> run(date, calls)).isInstanceOf(BadRequestException.class)
            .hasMessage("Invalid train file");
    }

    @Test
    void aPlainDayPutsEveryKindOfTimeOnTheRunningDate() {
        List<Times> result = run(DAY,
            call("Alpha Junction", "10:00", "10:05", null, "10:01", "10:06", null),
            call("Beta Parkway", null, null, "10:30", null, null, "10:31"));

        assertThat(result).containsExactly(
            times("Alpha Junction", "2030-03-04T10:00:00Z", "2030-03-04T10:05:00Z", null,
                "2030-03-04T10:01:00Z", "2030-03-04T10:06:00Z", null),
            times("Beta Parkway", null, null, "2030-03-04T10:30:00Z", null, null, "2030-03-04T10:31:00Z"));
    }

    @Test
    void halfMinutesAreKept() {
        List<Times> result = run(DAY, pass("12:00:30"), pass("12:01:30"));

        assertThat(result.get(0).plannedPass()).isEqualTo(Instant.parse("2030-03-04T12:00:30Z"));
        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse("2030-03-04T12:01:30Z"));
    }

    @Test
    void midnightBetweenTwoCallsMakesTheSecondTheNextDay() {
        List<Times> result = run(DAY, pass("23:50"), pass("00:10"));

        assertThat(result.get(0).plannedPass()).isEqualTo(Instant.parse("2030-03-04T23:50:00Z"));
        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse("2030-03-05T00:10:00Z"));
    }

    @Test
    void midnightInsideOneCallMakesTheDepartureTheNextDay() {
        List<Times> result = run(DAY, call("Alpha Junction", "23:58", "00:01", null, null, null, null), filler2());

        assertThat(result.get(0)).isEqualTo(times("Alpha Junction", "2030-03-04T23:58:00Z",
            "2030-03-05T00:01:00Z", null, null, null, null));
    }

    /** A call after midnight has passed, for tests where the first call crosses it. */
    private static Call filler2() {
        return pass("00:30");
    }

    @Test
    void aPlannedTimeExactlyTwelveHoursEarlierIsRefused() {
        assertRefused(DAY, pass("14:00"), pass("02:00"));
    }

    @Test
    void aPlannedTimeTwelveHoursAndThirtySecondsEarlierIsTheNextDay() {
        List<Times> result = run(DAY, pass("14:00"), pass("01:59:30"));

        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse("2030-03-05T01:59:30Z"));
    }

    @Test
    void aPlannedTimeFiveMinutesEarlierIsRefused() {
        assertRefused(DAY, pass("14:00"), pass("13:55"));
    }

    @Test
    void aPlannedTimeEqualToTheOneBeforeIsFine() {
        List<Times> result = run(DAY, pass("14:00"), pass("14:00"));

        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse("2030-03-04T14:00:00Z"));
    }

    @Test
    void aPlannedDepartureEarlierThanItsArrivalByAFewMinutesIsRefused() {
        assertRefused(DAY, call("Alpha Junction", "10:05", "10:00", null, null, null, null), filler());
    }

    @Test
    void theDayNeverGoesBackAfterMidnight() {
        List<Times> result = run(DAY, pass("23:50"), pass("00:10"), pass("01:00"), pass("12:00"));

        assertThat(result.get(2).plannedPass()).isEqualTo(Instant.parse("2030-03-05T01:00:00Z"));
        assertThat(result.get(3).plannedPass()).isEqualTo(Instant.parse("2030-03-05T12:00:00Z"));
    }

    @Test
    void anActualArrivalAndDepartureLateAcrossMidnightAreTheNextDay() {
        List<Times> result = run(DAY,
            call("Alpha Junction", "23:50", "23:58", null, "23:52", "00:03", null), filler2());

        assertThat(result.get(0).actualArrival()).isEqualTo(Instant.parse("2030-03-04T23:52:00Z"));
        assertThat(result.get(0).actualDeparture()).isEqualTo(Instant.parse("2030-03-05T00:03:00Z"));
    }

    @Test
    void anActualPassLateAcrossMidnightIsTheNextDay() {
        List<Times> result = run(DAY, call("Alpha Junction", null, null, "23:58", null, null, "00:03"), filler2());

        assertThat(result.get(0).actualPass()).isEqualTo(Instant.parse("2030-03-05T00:03:00Z"));
    }

    @Test
    void anActualArrivalAndDepartureEarlyAcrossMidnightAreTheDayBefore() {
        List<Times> result = run(DAY, pass("23:50"),
            call("Alpha Junction", "00:02", "00:03", null, "23:59", "23:59:30", null));

        assertThat(result.get(1)).isEqualTo(times("Alpha Junction", "2030-03-05T00:02:00Z", "2030-03-05T00:03:00Z",
            null, "2030-03-04T23:59:00Z", "2030-03-04T23:59:30Z", null));
    }

    @Test
    void anActualPassEarlyAcrossMidnightIsTheDayBefore() {
        List<Times> result = run(DAY, pass("23:50"), call("Alpha Junction", null, null, "00:02", null, null, "23:59"));

        assertThat(result.get(1).actualPass()).isEqualTo(Instant.parse("2030-03-04T23:59:00Z"));
    }

    @Test
    void actualTimesOfAPlannedPassAreAgainstThePass() {
        List<Times> result = run(DAY, pass("23:50"),
            call("Alpha Junction", null, null, "00:02", "23:59", "23:59:30", null));

        assertThat(result.get(1).actualArrival()).isEqualTo(Instant.parse("2030-03-04T23:59:00Z"));
        assertThat(result.get(1).actualDeparture()).isEqualTo(Instant.parse("2030-03-04T23:59:30Z"));
    }

    @Test
    void anActualArrivalOfACallPlannedOnlyToDepartIsAgainstTheDeparture() {
        List<Times> result = run(DAY, call("Alpha Junction", null, "10:30", null, "10:25", null, null), filler());

        assertThat(result.get(0).actualArrival()).isEqualTo(Instant.parse("2030-03-04T10:25:00Z"));
    }

    @Test
    void anActualArrivalIsAgainstThePlannedArrivalAndAnActualDepartureAgainstThePlannedDeparture() {
        List<Times> result = run(DAY, call("Alpha Junction", "10:00", "22:00", null, "04:00", "04:00", null), filler());

        assertThat(result.get(0).actualArrival()).isEqualTo(Instant.parse("2030-03-04T04:00:00Z"));
        assertThat(result.get(0).actualDeparture()).isEqualTo(Instant.parse("2030-03-05T04:00:00Z"));
    }

    @Test
    void theTwelveHoursAreOnTheClockWhenTheClocksGoForward() {
        List<Times> result = run("2030-03-31", pass("12:45"), pass("00:30"));

        assertThat(result.get(0).plannedPass()).isEqualTo(Instant.parse("2030-03-31T11:45:00Z"));
        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse("2030-03-31T23:30:00Z"));
    }

    @Test
    void theTwelveHoursAreOnTheClockWhenTheClocksGoBack() {
        assertRefused("2030-10-27", pass("11:45"), pass("00:30"));
    }

    @Test
    void aPlannedTimeWhoseInstantIsBeforeTheOneBeforeItIsRefusedAfterATimeInTheSkippedHour() {
        assertRefused("2030-03-31", pass("01:30"), pass("02:15"));
    }

    @ParameterizedTest
    @CsvSource({
        "02:30, 2030-03-31T01:30:00Z",
        "02:45, 2030-03-31T01:45:00Z"})
    void aPlannedTimeAfterOneInTheSkippedHourIsFineWhenItsInstantDoesNotGoBack(String clock, String expected) {
        List<Times> result = run("2030-03-31", pass("01:30"), pass(clock));

        assertThat(result.get(0).plannedPass()).isEqualTo(Instant.parse("2030-03-31T01:30:00Z"));
        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse(expected));
    }

    @Test
    void anActualPassOfAPlannedStopIsAgainstTheArrival() {
        List<Times> result = run(DAY, call("Alpha Junction", "10:00", "22:00", null, null, null, "04:00"), filler());

        assertThat(result.get(0).actualPass()).isEqualTo(Instant.parse("2030-03-04T04:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
        "22:00, 10:00, 2030-03-04T22:00:00Z",
        "10:00, 22:00, 2030-03-05T10:00:00Z",
        "21:59:30, 10:00, 2030-03-04T21:59:30Z",
        "22:00:30, 10:00, 2030-03-03T22:00:30Z",
        "10:00:30, 22:00, 2030-03-04T10:00:30Z",
        "09:59:30, 22:00, 2030-03-05T09:59:30Z"})
    void anActualTimeAboutTwelveHoursFromItsReferenceGoesToTheNearerDayAndATieToTheLater(
        String actual, String planned, String expected) {
        List<Times> result = run(DAY, call("Alpha Junction", null, null, planned, null, null, actual), filler());

        assertThat(result.get(0).actualPass()).isEqualTo(Instant.parse(expected));
    }

    @Test
    void anActualArrivalAfterItsDepartureIsRefused() {
        assertRefused(DAY, call("Alpha Junction", "10:00", "10:10", null, "10:06", "10:05:30", null), filler());
    }

    @Test
    void anActualArrivalEqualToItsDepartureIsFine() {
        List<Times> result = run(DAY, call("Alpha Junction", "10:00", "10:10", null, "10:05", "10:05", null), filler());

        assertThat(result.get(0).actualArrival()).isEqualTo(Instant.parse("2030-03-04T10:05:00Z"));
        assertThat(result.get(0).actualDeparture()).isEqualTo(Instant.parse("2030-03-04T10:05:00Z"));
    }

    @Test
    void theSameClockTimeInSummerIsAnHourEarlierInUtcThanInWinter() {
        assertThat(run("2030-07-08", pass("12:00"), filler()).get(0).plannedPass())
            .isEqualTo(Instant.parse("2030-07-08T11:00:00Z"));
        assertThat(run("2030-01-07", pass("12:00"), filler()).get(0).plannedPass())
            .isEqualTo(Instant.parse("2030-01-07T12:00:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
        "00:59, 2030-03-31T00:59:00Z",
        "01:00, 2030-03-31T01:00:00Z",
        "01:30, 2030-03-31T01:30:00Z",
        "02:00, 2030-03-31T01:00:00Z",
        "02:30, 2030-03-31T01:30:00Z",
        "03:00, 2030-03-31T02:00:00Z"})
    void theClocksGoingForwardTakeATimeInTheGapAsTheZoneResolvesIt(String clock, String expected) {
        List<Times> result = run("2030-03-31", pass("00:00"), pass(clock));

        assertThat(result.get(0).plannedPass()).isEqualTo(Instant.parse("2030-03-31T00:00:00Z"));
        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @CsvSource({
        "00:59, 2030-10-26T23:59:00Z",
        "01:00, 2030-10-27T00:00:00Z",
        "01:30, 2030-10-27T00:30:00Z",
        "02:00, 2030-10-27T02:00:00Z",
        "02:59, 2030-10-27T02:59:00Z"})
    void theClocksGoingBackTakeATimeInTheRepeatedHourAsTheEarlierOne(String clock, String expected) {
        List<Times> result = run("2030-10-27", pass("00:00"), pass(clock));

        assertThat(result.get(0).plannedPass()).isEqualTo(Instant.parse("2030-10-26T23:00:00Z"));
        assertThat(result.get(1).plannedPass()).isEqualTo(Instant.parse(expected));
    }

    @Test
    void theWorkedExampleComesToItsInstants() {
        TrainFile file = TrainFileSamples.read(TrainFileSamples.example());
        TrainFileRules.validate(file);

        assertThat(TrainTimes.instants(file)).containsExactly(
            times("Alpha Junction", null, "2030-03-04T22:00:00Z", null, null, "2030-03-04T22:01:30Z", null),
            times("Beta Parkway", "2030-03-04T22:30:00Z", "2030-03-04T22:32:00Z", null,
                "2030-03-04T22:31:30Z", "2030-03-04T22:34:00Z", null),
            times("Gamma Loop", null, null, "2030-03-04T22:50:30Z", null, null, "2030-03-04T22:53:00Z"),
            times("Delta Cross", null, null, "2030-03-04T23:10:00Z", "2030-03-04T23:12:00Z",
                "2030-03-04T23:13:30Z", null),
            times("Epsilon Green", "2030-03-04T23:40:00Z", "2030-03-04T23:41:00Z", null, null, null, null),
            times("Zeta Sidings", null, null, "2030-03-04T23:59:30Z", null, null, "2030-03-05T00:02:00Z"),
            times("Eta Works", null, null, "2030-03-05T00:20:00Z", null, null, "2030-03-05T00:22:30Z"),
            times("Theta Central", "2030-03-05T00:45:00Z", null, null, "2030-03-05T00:47:30Z", null, null));
    }

    @Test
    void theSpanOfTheWorkedExampleIsFromItsFirstDepartureToItsLastActualArrival() {
        TrainFile file = TrainFileSamples.read(TrainFileSamples.example());

        assertThat(TrainTimes.span(TrainTimes.instants(file)))
            .isEqualTo(new Span(Instant.parse("2030-03-04T22:00:00Z"), Instant.parse("2030-03-05T00:47:30Z")));
    }

    @Test
    void theSpanTakesActualTimesWhenTheyAreTheEarliestAndTheLatest() {
        List<Times> result = run(DAY,
            call("Alpha Junction", null, "10:00", null, null, "09:58", null),
            call("Beta Parkway", "10:30", null, null, "10:34", null, null));

        assertThat(TrainTimes.span(result))
            .isEqualTo(new Span(Instant.parse("2030-03-04T09:58:00Z"), Instant.parse("2030-03-04T10:34:00Z")));
    }
}
