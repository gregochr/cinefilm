package gregochr.cinefilm.journey;

import java.util.List;

/**
 * The request of {@code POST /api/admin/journeys}: one logger file, as the Admin page trims it (names and ranges in
 * {@code docs/engineering/journey-data-plan.md}, "The import's request"). Every field is boxed so a missing one
 * arrives as null and is refused, or accepted, by {@link JourneyFileRules}; {@link JourneyFileReader} refuses any
 * name these records do not have.
 */
public record JourneyFile(Long runStartedMs, Integer loggerVersion, String sourceFile, String osVersion,
                          String travelClass, DeviceInfo device, List<Sample> samples) {

    /** The kind of laptop that logged the run: all five values, or the file has no device. */
    public record DeviceInfo(String model, String cpu, Integer cores, Integer threads, Long memoryBytes) {
    }

    /** One round of the logger; {@code round} and {@code at} are all a failed round holds. */
    public record Sample(Integer round, Long at, String trainUnit, Long feedAt, Integer fixMode, Integer fixAgeS,
                         Double latitude, Double longitude, Double altitudeM, Double speedMps, Double courseDeg,
                         Integer satellites, Integer online, Integer usersOnline, Integer usersTotal,
                         Long downLimit, Long upLimit, Integer capLevel, Integer userClass, Long dataDownUsed,
                         Long dataUpUsed, Integer probeMs, Integer probeCode, String powerSource,
                         Integer batteryPercent, Integer lowPower, Integer cpuLimit, Double loadAvg, Double swapMb,
                         Integer wifiRssi, Integer wifiNoise, Double wifiRate, Integer wifiPhy, Integer wifiBand,
                         Integer wifiWidth, Integer wifiChannel, List<Modem> modems) {
    }

    /** One modem's state in a round. */
    public record Modem(Integer index, String deviceType, String deviceSubtype, String deviceState,
                        String linkState, String technology, Integer rssi, String operatorId, Long cellId) {
    }
}
