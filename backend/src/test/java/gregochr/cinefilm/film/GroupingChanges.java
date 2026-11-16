package gregochr.cinefilm.film;

import java.time.Instant;
import java.util.List;

/** Reads the grouping log through the production query, for tests. */
public final class GroupingChanges {

    private static final Instant FROM = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2999-01-01T00:00:00Z");

    private GroupingChanges() {
    }

    /** Every logged change, newest first (the report's own query over all time). */
    public static List<GroupingChange> all(GroupingChangeRepository changes) {
        return changes.findByHappenedAtBetweenOrderByHappenedAtDescIdDesc(FROM, TO);
    }
}
