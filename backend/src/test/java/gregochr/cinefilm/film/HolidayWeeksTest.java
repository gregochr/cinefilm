package gregochr.cinefilm.film;

import static org.assertj.core.api.Assertions.assertThat;

import gregochr.cinefilm.film.HolidayWeeks.Holiday;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HolidayWeeksTest {

    private static Optional<Holiday> on(String date) {
        return HolidayWeeks.holidayFor(LocalDate.parse(date));
    }

    @Test
    void christmasWeekOf2026RunsFromMondayFourteenthToSundayTwentieth() {
        assertThat(on("2026-12-13")).as("Sunday before").isEmpty();
        assertThat(on("2026-12-14")).as("Monday").contains(Holiday.CHRISTMAS);
        assertThat(on("2026-12-18")).contains(Holiday.CHRISTMAS);
        assertThat(on("2026-12-20")).as("Sunday").contains(Holiday.CHRISTMAS);
        assertThat(on("2026-12-21")).as("Monday after").isEmpty();
    }

    @Test
    void halloweenWeekOf2026RunsFromMondayNineteenthToSundayTwentyFifth() {
        assertThat(on("2026-10-18")).as("Sunday before").isEmpty();
        assertThat(on("2026-10-19")).as("Monday").contains(Holiday.HALLOWEEN);
        assertThat(on("2026-10-24")).contains(Holiday.HALLOWEEN);
        assertThat(on("2026-10-25")).as("Sunday").contains(Holiday.HALLOWEEN);
        assertThat(on("2026-10-26")).as("Monday after").isEmpty();
    }

    @Test
    void halloweenDayItselfIsAfterTheWeekAndNotAHoliday() {
        assertThat(on("2026-10-31")).isEmpty();
        assertThat(on("2026-12-25")).isEmpty();
    }

    @ParameterizedTest(name = "18 Dec {0} is a {1}: week starts {2}")
    @CsvSource({"2023, Monday, 2023-12-18", "2024, Wednesday, 2024-12-16", "2025, Thursday, 2025-12-15",
        "2026, Friday, 2026-12-14", "2027, Saturday, 2027-12-13", "2028, Monday, 2028-12-18",
        "2022, Sunday, 2022-12-12", "2021, Saturday, 2021-12-13", "2029, Tuesday, 2029-12-17"})
    void christmasWeekStartsOnTheMondayOnOrBeforeEighteenthDecemberInAnyYear(int year, String weekday, String monday) {
        LocalDate eighteenth = LocalDate.of(year, 12, 18);
        LocalDate start = LocalDate.parse(monday);
        assertThat(eighteenth.getDayOfWeek().toString()).isEqualToIgnoringCase(weekday);
        assertThat(start.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(HolidayWeeks.holidayFor(start.minusDays(1))).isEmpty();
        IntStream.rangeClosed(0, 6).forEach(d ->
            assertThat(HolidayWeeks.holidayFor(start.plusDays(d))).as(start.plusDays(d).toString())
                .contains(Holiday.CHRISTMAS));
        assertThat(HolidayWeeks.holidayFor(start.plusDays(7))).isEmpty();
    }

    @ParameterizedTest(name = "24 Oct {0} is a {1}: week starts {2}")
    @CsvSource({"2022, Monday, 2022-10-24", "2023, Tuesday, 2023-10-23", "2024, Thursday, 2024-10-21",
        "2025, Friday, 2025-10-20", "2026, Saturday, 2026-10-19", "2021, Sunday, 2021-10-18",
        "2027, Sunday, 2027-10-18", "2028, Tuesday, 2028-10-23"})
    void halloweenWeekStartsOnTheMondayOnOrBeforeTwentyFourthOctoberInAnyYear(int year, String weekday,
                                                                              String monday) {
        LocalDate twentyFourth = LocalDate.of(year, 10, 24);
        LocalDate start = LocalDate.parse(monday);
        assertThat(twentyFourth.getDayOfWeek().toString()).isEqualToIgnoringCase(weekday);
        assertThat(start).isEqualTo(twentyFourth.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        assertThat(HolidayWeeks.holidayFor(start.minusDays(1))).isEmpty();
        IntStream.rangeClosed(0, 6).forEach(d ->
            assertThat(HolidayWeeks.holidayFor(start.plusDays(d))).as(start.plusDays(d).toString())
                .contains(Holiday.HALLOWEEN));
        assertThat(HolidayWeeks.holidayFor(start.plusDays(7))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01", "2026-03-31", "2026-07-04", "2026-11-19", "2026-12-31", "2027-01-01",
        "2027-02-28"})
    void otherDatesAreNotHolidays(String date) {
        assertThat(on(date)).isEmpty();
    }

    @Test
    void theNewYearEdgeIsNotAHolidayInEitherYear() {
        assertThat(on("2025-12-31")).isEmpty();
        assertThat(on("2026-01-04")).isEmpty();
    }
}
