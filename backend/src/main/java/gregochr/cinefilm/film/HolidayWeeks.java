package gregochr.cinefilm.film;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * The two holiday periods of the Home weekly theme. Each starts on a Monday and runs until the day itself (so the
 * ordinary rotation returns on 1 Nov and 26 Dec, which need not be a Monday): Halloween from the Monday on or before
 * 24 October through 31 October, Christmas from the Monday on or before 18 December through 25 December (both ends
 * included; 8 to 14 days).
 */
public final class HolidayWeeks {

    /** A holiday that takes over the weekly theme. */
    public enum Holiday {
        /** Horror films. */
        HALLOWEEN,
        /** Christmas films. */
        CHRISTMAS
    }

    /** A holiday period: from a Monday to the holiday itself, both ends included. */
    public record Week(LocalDate start, LocalDate end) {
    }

    private HolidayWeeks() {
    }

    /**
     * Christmas period of {@code today}'s year, or, once it has ended (today is after 25 December), the next year's.
     */
    public static Week christmasWeekFrom(LocalDate today) {
        Week thisYear = weekOf(today.getYear(), Month.DECEMBER, 18, 25);
        return today.isAfter(thisYear.end()) ? weekOf(today.getYear() + 1, Month.DECEMBER, 18, 25) : thisYear;
    }

    /** The holiday whose period contains {@code date}, or empty on every other day. */
    public static Optional<Holiday> holidayFor(LocalDate date) {
        if (inWeekOf(date, Month.OCTOBER, 24, 31)) {
            return Optional.of(Holiday.HALLOWEEN);
        }
        if (inWeekOf(date, Month.DECEMBER, 18, 25)) {
            return Optional.of(Holiday.CHRISTMAS);
        }
        return Optional.empty();
    }

    private static boolean inWeekOf(LocalDate date, Month month, int day, int lastDay) {
        Week week = weekOf(date.getYear(), month, day, lastDay);
        return !date.isBefore(week.start()) && !date.isAfter(week.end());
    }

    private static Week weekOf(int year, Month month, int day, int lastDay) {
        LocalDate start = LocalDate.of(year, month, day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new Week(start, LocalDate.of(year, month, lastDay));
    }
}
