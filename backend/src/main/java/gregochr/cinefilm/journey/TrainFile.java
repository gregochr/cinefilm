package gregochr.cinefilm.journey;

import java.util.List;

/**
 * The request of the coming {@code PUT /api/admin/journeys/{id}/train}: one train's run as copied by hand from the
 * railway's running data (names and ranges in {@code docs/engineering/journey-data-plan.md}, "The train's file").
 * Everything is text, so a missing value arrives as null and is refused, or accepted, by {@link TrainFileRules};
 * {@link TrainFileReader} refuses any name these records do not have.
 */
public record TrainFile(String uid, String date, String headcode, String operator, String source,
                        List<Call> calls) {

    /** One timing point: where, and the planned and actual clock times there ({@code HH:MM} or {@code HH:MM:SS}). */
    public record Call(String location, String plannedArrival, String plannedDeparture, String plannedPass,
                       String actualArrival, String actualDeparture, String actualPass) {
    }
}
