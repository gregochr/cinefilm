package gregochr.cinefilm.health;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.health.HealthDtos.ClientInfo;
import gregochr.cinefilm.health.HealthDtos.HealthReport;
import gregochr.cinefilm.transcode.Ladder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules a health report must keep. Every report comes from the browser and is untrusted: each field is bounded,
 * so one bad value is refused as a whole and nothing odd is stored. Every failure is the same
 * {@link BadRequestException}, and nothing else is ever thrown, whatever is null.
 */
public final class HealthRules {

    /** The message of every refusal. */
    public static final String INVALID = "Invalid health report";

    /** Largest value any figure may have. */
    static final long MAX_NUMBER = 2_000_000_000L;

    /** Most entries {@code minutes} may hold (six hours). */
    static final int MAX_MINUTES = 360;

    private static final int MINUTE_FIELDS = 5;
    private static final int RUNG_CODE_INDEX = 2;
    private static final long MAX_RUNG_CODE = 6;
    private static final int MAX_OPERATOR = 100;
    private static final int MAX_USER_AGENT = 300;
    private static final Set<String> ENVIRONMENTS = Set.of("home", "train", "away", "unknown");
    private static final Set<String> ENGINES = Set.of("hlsjs", "native", "direct");
    private static final Set<String> RUNGS = Set.copyOf(
        Ladder.QUALITY_IDS.stream().filter(id -> !Ladder.AUTO.equals(id)).toList());
    private static final Pattern SCREEN = Pattern.compile("\\d{1,5}x\\d{1,5}");

    private HealthRules() {
    }

    /** Returns normally for a valid report; throws {@link BadRequestException} for anything else. */
    public static void validate(HealthReport r) {
        refuseUnless(r != null && r.client() != null);
        refuseUnless(atLeast(r.seq(), 1) && atLeast(r.editionId(), 1));
        refuseUnless(r.environment() != null && ENVIRONMENTS.contains(r.environment()));
        refuseUnless(r.engine() == null || ENGINES.contains(r.engine()));
        refuseUnless(r.quality() != null && Ladder.QUALITY_IDS.contains(r.quality()));
        refuseUnless(r.operator() == null || r.operator().length() <= MAX_OPERATOR);
        refuseUnless(r.autoplayRefused() != null && r.fellBack() != null);
        refuseUnless(figures(r.elapsedMs(), r.watchedMs(), r.stallCount(), r.stallMs(), r.longestStallMs(),
            r.seekCount(), r.seekWaitMs(), r.reloadCount(), r.reloadMs(), r.reconnectCount(), r.reconnectMs(),
            r.recoveries(), r.stepsDown(), r.stepsUp(), r.netErrors(), r.timeouts(), r.kbpsSamples()));
        refuseUnless(optionalFigures(r.latencyMs(), r.startupMs(), r.kbpsLow(), r.kbpsMedian(), r.kbpsHigh()));
        refuseUnless(rungMs(r.rungMs()));
        refuseUnless(minutes(r.minutes()));
        refuseUnless(client(r.client()));
    }

    private static void refuseUnless(boolean ok) {
        if (!ok) {
            throw new BadRequestException(INVALID);
        }
    }

    private static boolean atLeast(Long value, long min) {
        return value != null && value >= min && value <= MAX_NUMBER;
    }

    private static boolean figures(Long... values) {
        for (Long value : values) {
            if (!atLeast(value, 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean optionalFigures(Long... values) {
        for (Long value : values) {
            if (value != null && !atLeast(value, 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rungMs(Map<String, Long> rungMs) {
        if (rungMs == null) {
            return false;
        }
        for (Map.Entry<String, Long> entry : rungMs.entrySet()) {
            if (!RUNGS.contains(entry.getKey()) || !atLeast(entry.getValue(), 0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean minutes(List<List<Long>> minutes) {
        if (minutes == null || minutes.size() > MAX_MINUTES) {
            return false;
        }
        for (List<Long> minute : minutes) {
            if (!minute(minute)) {
                return false;
            }
        }
        return true;
    }

    private static boolean minute(List<Long> minute) {
        if (minute == null || minute.size() != MINUTE_FIELDS) {
            return false;
        }
        for (Long value : minute) {
            if (!atLeast(value, 0)) {
                return false;
            }
        }
        return minute.get(RUNG_CODE_INDEX) <= MAX_RUNG_CODE;
    }

    private static boolean client(ClientInfo client) {
        return client.userAgent() != null && client.userAgent().length() <= MAX_USER_AGENT
            && client.screen() != null && SCREEN.matcher(client.screen()).matches()
            && optionalFigures(client.cores());
    }
}
