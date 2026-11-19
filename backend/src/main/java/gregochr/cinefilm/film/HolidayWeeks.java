package gregochr.cinefilm.film;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * The two holiday weeks of the Home weekly theme. Halloween week runs Monday to Sunday starting on the Monday on or
 * before 24 October; Christmas week likewise from the Monday on or before 18 December. Both are ordinary weeks, so the
 * theme still changes on a Monday.
 */
public final class HolidayWeeks {

    /** A holiday that takes over the weekly theme. */
    public enum Holiday {
        /** Horror films. */
        HALLOWEEN,
        /** Christmas films. */
        CHRISTMAS
    }

    /** A holiday week: Monday to Sunday, both ends included. */
    public record Week(LocalDate start, LocalDate end) {
    }

    private HolidayWeeks() {
    }

    /**
     * Christmas week of {@code today}'s year, or, once that week has ended (today is after its Sunday), the next
     * year's.
     */
    public static Week christmasWeekFrom(LocalDate today) {
        Week thisYear = weekOf(today.getYear(), Month.DECEMBER, 18);
        return today.isAfter(thisYear.end()) ? weekOf(today.getYear() + 1, Month.DECEMBER, 18) : thisYear;
    }

    /** The holiday whose week contains {@code date}, or empty in every other week. */
    public static Optional<Holiday> holidayFor(LocalDate date) {
        if (inWeekOf(date, Month.OCTOBER, 24)) {
            return Optional.of(Holiday.HALLOWEEN);
        }
        if (inWeekOf(date, Month.DECEMBER, 18)) {
            return Optional.of(Holiday.CHRISTMAS);
        }
        return Optional.empty();
    }

    private static boolean inWeekOf(LocalDate date, Month month, int day) {
        Week week = weekOf(date.getYear(), month, day);
        return !date.isBefore(week.start()) && !date.isAfter(week.end());
    }

    private static Week weekOf(int year, Month month, int day) {
        LocalDate start = LocalDate.of(year, month, day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Week(start, start.plusDays(6));
    }
}
