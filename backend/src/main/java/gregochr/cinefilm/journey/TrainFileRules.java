package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.journey.TrainFile.Call;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The rules a train's file must keep. The file comes from the browser and is untrusted: each value is bounded, so
 * one bad value refuses the whole file. Every failure is the same {@link BadRequestException}, and nothing else is
 * ever thrown, whatever is null. The ranges are the plan's section "The train's file"
 * ({@code journey-data-plan.md}), kept as tables here. The rules that need the times as instants (an arrival not
 * after its departure, planned times not going back, midnight) come with the clock arithmetic, not here.
 */
public final class TrainFileRules {

    /** The message of every refusal. */
    public static final String INVALID = "Invalid train file";

    /** Fewest calls a file holds: an origin and a destination. */
    static final int MIN_CALLS = 2;

    /** Most calls a file holds. */
    static final int MAX_CALLS = 300;

    static final LocalDate MIN_DATE = LocalDate.of(2020, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2100, 1, 1);

    /** {@code HH:MM} or {@code HH:MM:SS}: two ASCII digits each, seconds 00 or 30. */
    private static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9](:(00|30))?");

    /** {@code YYYY-MM-DD}: four ASCII digits, two, two (ISO parsing alone would take a signed, padded year). */
    private static final Pattern DATE_FORM = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /** A text: its name, its longest length (the shortest is 1), whether it must be there, and where to read it. */
    record Text<T>(String name, int max, boolean required, Function<T, String> get) {
    }

    /** A clock time of a call: its name and where to read it. */
    record Clock(String name, Function<Call, String> get) {
    }

    static final List<Text<TrainFile>> FILE_TEXTS = List.of(
        new Text<>("uid", 10, true, TrainFile::uid),
        new Text<>("headcode", 10, true, TrainFile::headcode),
        new Text<>("operator", 100, false, TrainFile::operator),
        new Text<>("source", 200, false, TrainFile::source));

    static final List<Text<Call>> CALL_TEXTS = List.of(new Text<>("location", 100, true, Call::location));

    static final List<Clock> CLOCKS = List.of(
        new Clock("plannedArrival", Call::plannedArrival),
        new Clock("plannedDeparture", Call::plannedDeparture),
        new Clock("plannedPass", Call::plannedPass),
        new Clock("actualArrival", Call::actualArrival),
        new Clock("actualDeparture", Call::actualDeparture),
        new Clock("actualPass", Call::actualPass));

    private TrainFileRules() {
    }

    /** Returns normally for a valid file; throws {@link BadRequestException} for anything else. */
    public static void validate(TrainFile file) {
        refuseUnless(file != null && texts(file, FILE_TEXTS) && date(file.date()));
        List<Call> calls = file.calls();
        refuseUnless(calls != null && calls.size() >= MIN_CALLS && calls.size() <= MAX_CALLS);
        for (Call call : calls) {
            refuseUnless(call != null && texts(call, CALL_TEXTS) && times(call));
        }
    }

    private static void refuseUnless(boolean ok) {
        if (!ok) {
            throw new BadRequestException(INVALID);
        }
    }

    private static <T> boolean texts(T record, List<Text<T>> texts) {
        for (Text<T> text : texts) {
            String value = text.get().apply(record);
            if (value == null ? text.required() : !printable(value, text.max())) {
                return false;
            }
        }
        return true;
    }

    private static boolean printable(String value, int max) {
        return !value.isEmpty() && value.length() <= max && value.chars().allMatch(c -> c >= ' ' && c <= '~');
    }

    /** {@code YYYY-MM-DD}, a real calendar date inside the range. */
    private static boolean date(String value) {
        if (value == null || !DATE_FORM.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return !date.isBefore(MIN_DATE) && !date.isAfter(MAX_DATE);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Every given time is a clock time; a call has planned times, and neither kind mixes a pass with a stop. */
    private static boolean times(Call call) {
        for (Clock clock : CLOCKS) {
            String value = clock.get().apply(call);
            if (value != null && !CLOCK.matcher(value).matches()) {
                return false;
            }
        }
        boolean planned = call.plannedArrival() != null || call.plannedDeparture() != null
            || call.plannedPass() != null;
        return planned && consistent(call.plannedArrival(), call.plannedDeparture(), call.plannedPass())
            && consistent(call.actualArrival(), call.actualDeparture(), call.actualPass());
    }

    /** A pass is never given with an arrival or a departure. */
    private static boolean consistent(String arrival, String departure, String pass) {
        return pass == null || (arrival == null && departure == null);
    }
}
