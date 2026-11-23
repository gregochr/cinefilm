package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.TrainFile.Call;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Turns the clock times of a train's file into instants. The file gives a running date and clock times as the
 * railway shows them; the server makes the instants so that nobody has to get the time zone, summer time or midnight
 * right by hand. The file must already have passed {@link TrainFileRules#validate}; this class checks no form again.
 *
 * <p>All times are Britain's clock, {@link #BRITAIN}. A clock time that does not exist (the hour skipped when the
 * clocks go forward) or exists twice (the hour repeated when they go back) is taken as
 * {@link ZonedDateTime#of(LocalDate, LocalTime, ZoneId)} resolves it: a time in the gap is moved on by the length of
 * the gap, and a time in the repeated hour is the earlier of its two instants.
 *
 * <p>Planned times are walked in order (inside a call, arrival, then departure, or the pass). The walk compares clock
 * times, so its twelve hours are twelve hours on the clock on every day of the year, the days the clocks change
 * included. A planned time whose instant would be before the one before it is refused too, which can happen only
 * after a time in the hour that is skipped. The first is on the running date; a planned time earlier than the one
 * before it by more than twelve hours is on the next day (midnight has passed), by twelve hours or less it is
 * refused, and the day only ever goes forward. Each actual time is put on the day before, the same day or the day
 * after that brings it nearest to its reference: the call's planned time of the same kind, or, when there is none,
 * the call's first planned time. Exactly as near on two days goes to the later day. Every refusal is the one
 * {@link BadRequestException} of {@link TrainFileRules}.
 */
public final class TrainTimes {

    /** Britain's clock. */
    public static final ZoneId BRITAIN = ZoneId.of("Europe/London");

    private static final Duration HALF_DAY = Duration.ofHours(12);

    /** One call's location and its six times as instants; null where the file has none. */
    public record Times(String location, Instant plannedArrival, Instant plannedDeparture, Instant plannedPass,
                        Instant actualArrival, Instant actualDeparture, Instant actualPass) {

        private Stream<Instant> given() {
            return Stream.of(plannedArrival, plannedDeparture, plannedPass, actualArrival, actualDeparture,
                actualPass).filter(Objects::nonNull);
        }
    }

    /** The earliest and the latest of all the instants of a file. */
    public record Span(Instant earliest, Instant latest) {
    }

    private TrainTimes() {
    }

    /** The calls of a valid file, in order, with their times as instants. */
    public static List<Times> instants(TrainFile file) {
        Walk walk = new Walk(LocalDate.parse(file.date()));
        List<Times> times = new ArrayList<>();
        for (Call call : file.calls()) {
            Instant plannedArrival = walk.next(call.plannedArrival());
            Instant plannedDeparture = walk.next(call.plannedDeparture());
            Instant plannedPass = walk.next(call.plannedPass());
            Instant first = Stream.of(plannedArrival, plannedDeparture, plannedPass).filter(Objects::nonNull)
                .findFirst().orElseThrow();
            Instant actualArrival = nearest(call.actualArrival(), plannedArrival, first);
            Instant actualDeparture = nearest(call.actualDeparture(), plannedDeparture, first);
            Instant actualPass = nearest(call.actualPass(), plannedPass, first);
            if (actualArrival != null && actualDeparture != null && actualArrival.isAfter(actualDeparture)) {
                throw new BadRequestException(TrainFileRules.INVALID);
            }
            times.add(new Times(call.location(), plannedArrival, plannedDeparture, plannedPass, actualArrival,
                actualDeparture, actualPass));
        }
        return times;
    }

    /** The earliest and the latest instant among the given times; they must hold at least one instant. */
    public static Span span(List<Times> times) {
        List<Instant> all = times.stream().flatMap(Times::given).toList();
        return new Span(all.stream().min(Instant::compareTo).orElseThrow(),
            all.stream().max(Instant::compareTo).orElseThrow());
    }

    private static Instant at(LocalDate day, LocalTime time) {
        return ZonedDateTime.of(day, time, BRITAIN).toInstant();
    }

    /** The actual clock time on the day nearest to the reference; a tie goes to the later day. */
    private static Instant nearest(String clock, Instant kindReference, Instant first) {
        if (clock == null) {
            return null;
        }
        Instant reference = kindReference != null ? kindReference : first;
        LocalDate base = reference.atZone(BRITAIN).toLocalDate();
        LocalTime time = LocalTime.parse(clock);
        Instant best = at(base.minusDays(1), time);
        for (int offset = 0; offset <= 1; offset++) {
            Instant candidate = at(base.plusDays(offset), time);
            if (Duration.between(candidate, reference).abs().compareTo(Duration.between(best, reference).abs())
                <= 0) {
                best = candidate;
            }
        }
        return best;
    }

    /** The planned times in order: the day they are on, and the one before. */
    private static final class Walk {

        private LocalDate day;
        private LocalDateTime previous;
        private Instant previousInstant = Instant.MIN;

        Walk(LocalDate day) {
            this.day = day;
        }

        Instant next(String clock) {
            if (clock == null) {
                return null;
            }
            LocalTime time = LocalTime.parse(clock);
            LocalDateTime at = LocalDateTime.of(day, time);
            if (previous != null && at.isBefore(previous)) {
                if (Duration.between(at, previous).compareTo(HALF_DAY) <= 0) {
                    throw new BadRequestException(TrainFileRules.INVALID);
                }
                day = day.plusDays(1);
                at = LocalDateTime.of(day, time);
            }
            Instant instant = at.atZone(BRITAIN).toInstant();
            if (instant.isBefore(previousInstant)) {
                throw new BadRequestException(TrainFileRules.INVALID);
            }
            previous = at;
            previousInstant = instant;
            return instant;
        }
    }
}
