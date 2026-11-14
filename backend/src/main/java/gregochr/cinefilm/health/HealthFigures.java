package gregochr.cinefilm.health;

import gregochr.cinefilm.health.HealthDtos.HealthReport;

/**
 * The few figures of a health report that are also stored as columns, so Admin can filter and chart by them without
 * reading every report. One extraction, used when a viewing is first stored, when a newer report replaces it and
 * when the rows stored before the columns existed were filled in (V27), so the columns and the report never differ.
 *
 * <p>{@code hasProblem} is true when the report shows a stall, a reconnect, a step down to a lower rung
 * ({@code stepsDown}), a fall back to direct play ({@code fellBack}), a network error or a timeout. Missing figures
 * count as zero, since a stored report from an older release can lack any of them.
 *
 * @param watchedMs   the film played, in ms
 * @param stallCount  how many stalls
 * @param stallMs     how long the stalls took altogether, in ms
 * @param startupMs   time to the first frame, in ms; null when it never came
 * @param hasProblem  whether anything went wrong, as above
 * @param environment home, train, away or unknown; null when the report has none
 */
public record HealthFigures(long watchedMs, long stallCount, long stallMs, Long startupMs, boolean hasProblem,
                            String environment) {

    /** Longest environment the column holds; a longer one (never a valid report's) is stored as null. */
    static final int MAX_ENVIRONMENT = 16;

    /** Reads the figures out of a report; a missing figure is 0 (null for the start-up time and environment). */
    public static HealthFigures of(HealthReport report) {
        long stalls = zero(report.stallCount());
        boolean problem = stalls > 0 || zero(report.reconnectCount()) > 0 || zero(report.stepsDown()) > 0
            || Boolean.TRUE.equals(report.fellBack()) || zero(report.netErrors()) > 0 || zero(report.timeouts()) > 0;
        return new HealthFigures(zero(report.watchedMs()), stalls, zero(report.stallMs()), report.startupMs(),
            problem, environmentOf(report.environment()));
    }

    private static String environmentOf(String environment) {
        return environment != null && environment.length() <= MAX_ENVIRONMENT ? environment : null;
    }

    private static long zero(Long value) {
        return value == null ? 0 : value;
    }
}
