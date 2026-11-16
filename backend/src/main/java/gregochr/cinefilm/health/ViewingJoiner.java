package gregochr.cinefilm.health;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Joins the playbacks of one viewing. A player that reloads, or a pause that outlasts the page, opens a new playback
 * id, so one film watched once can be several rows. Rows go in oldest first (by {@code startedAt}); a row joins the
 * newest viewing of its film on its device when all of these hold, tested against that viewing's last part A:
 *
 * <ol>
 *   <li>the same film;</li>
 *   <li>the same device: the same user agent, and not empty;</li>
 *   <li>it started 0 to 6 hours after A's last report ({@link #MAX_GAP});</li>
 *   <li>it picks up where A stopped: when both carry positions, the new part's start position is within
 *       {@link #MAX_POSITION_DRIFT_MS} of A's end position; when either lacks them, the same environment and the same
 *       operator.</li>
 * </ol>
 *
 * <p>Only the newest viewing of a film on a device can take a part, so a viewing is a chain, never a tree: a row that
 * fails the rules starts a new viewing and becomes the one later rows are tested against. The plan is in
 * {@code docs/engineering/joined-viewings-plan.md}.
 */
final class ViewingJoiner {

    /** Longest gap between a part's last report and the next part's start that still joins. */
    static final Duration MAX_GAP = Duration.ofHours(6);

    /** Furthest the next part's start position may be from the last part's end position, in ms. */
    static final long MAX_POSITION_DRIFT_MS = 120_000;

    private ViewingJoiner() {
    }

    /**
     * One stored playback, as the joiner reads it. An unreadable stored report is a part with no operator, no user
     * agent and no positions.
     */
    record Part(String playbackId, long filmId, String filmTitle, Instant startedAt, Instant updatedAt,
                long watchedMs, long stallCount, long stallMs, Long startupMs, boolean hasProblem,
                String environment, String operator, String userAgent, Boolean touch, Long startPositionMs,
                Long endPositionMs, Map<String, Long> rungMs) {
    }

    /** One viewing: its parts, oldest first, never empty. */
    record Viewing(List<Part> parts) {

        Part first() {
            return parts.get(0);
        }

        Part last() {
            return parts.get(parts.size() - 1);
        }

        long watchedMs() {
            return parts.stream().mapToLong(Part::watchedMs).sum();
        }

        boolean hasProblem() {
            return parts.stream().anyMatch(Part::hasProblem);
        }
    }

    /** Joins {@code rows}, which must be oldest first; the viewings come back in the order they started. */
    static List<Viewing> join(List<Part> rows) {
        List<Viewing> viewings = new ArrayList<>();
        Map<String, Viewing> newest = new HashMap<>();
        for (Part row : rows) {
            if (row.userAgent() == null || row.userAgent().isEmpty()) {
                viewings.add(new Viewing(new ArrayList<>(List.of(row))));
                continue;
            }
            String device = row.filmId() + "\n" + row.userAgent();
            Viewing candidate = newest.get(device);
            if (candidate != null && continues(candidate.last(), row)) {
                candidate.parts().add(row);
            } else {
                Viewing started = new Viewing(new ArrayList<>(List.of(row)));
                viewings.add(started);
                newest.put(device, started);
            }
        }
        return viewings;
    }

    /** Whether {@code next} (same film and device already known) picks up where {@code last} stopped. */
    private static boolean continues(Part last, Part next) {
        Duration gap = Duration.between(last.updatedAt(), next.startedAt());
        if (gap.isNegative() || gap.compareTo(MAX_GAP) > 0) {
            return false;
        }
        if (last.endPositionMs() != null && next.startPositionMs() != null) {
            return Math.abs(next.startPositionMs() - last.endPositionMs()) <= MAX_POSITION_DRIFT_MS;
        }
        return Objects.equals(last.environment(), next.environment())
            && Objects.equals(last.operator(), next.operator());
    }
}
