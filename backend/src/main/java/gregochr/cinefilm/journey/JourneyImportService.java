package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import gregochr.cinefilm.journey.JourneyFile.DeviceInfo;
import gregochr.cinefilm.journey.JourneyFile.Modem;
import gregochr.cinefilm.journey.JourneyFile.Sample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores one train-feed logger file as journeys (plan: {@code journey-data-plan.md}, decisions 3 to 5): checks every
 * value, makes a journey for each stretch on one train unit, stores the samples and modems, and links the journeys to
 * the playback records watched on the way. One file is one transaction: whatever fails, nothing is stored.
 */
@Service
public class JourneyImportService {

    /** Longest request body accepted, in characters. */
    static final int MAX_BODY_LENGTH = 4_000_000;

    /** How far either side of a journey a playback record may lie and still be linked to it. */
    static final Duration LINK_MARGIN = Duration.ofMinutes(2);

    /** Most playback records one journey is linked to. */
    static final int MAX_LINKS = 200;

    private final JourneyRepository journeys;
    private final DeviceRepository devices;
    private final JourneySampleRepository samples;
    private final JourneyModemRepository modems;
    private final JourneyModemSampleRepository modemSamples;
    private final JourneyPlaybackRepository links;
    private final PlaybackHealthRepository playbacks;
    private final JourneyFileReader reader;
    private final Clock clock;

    /** Creates the service over the journey repositories, the playback records, the application mapper and clock. */
    public JourneyImportService(JourneyRepository journeys, DeviceRepository devices,
                                JourneySampleRepository samples, JourneyModemRepository modems,
                                JourneyModemSampleRepository modemSamples, JourneyPlaybackRepository links,
                                PlaybackHealthRepository playbacks, JsonMapper mapper, Clock clock) {
        this.journeys = journeys;
        this.devices = devices;
        this.samples = samples;
        this.modems = modems;
        this.modemSamples = modemSamples;
        this.links = links;
        this.playbacks = playbacks;
        this.reader = new JourneyFileReader(mapper);
        this.clock = clock;
    }

    /** One journey made by an import. */
    public record Imported(Long id, int segment, String trainUnit, int samples, Instant from, Instant to,
                           int linked) {
    }

    /** The answer of an import: the journeys it made, in segment order. */
    public record Result(List<Imported> journeys) {
    }

    /** One stretch of the file on one train unit, before it is stored. */
    private static final class Segment {
        private final List<Sample> samples = new ArrayList<>();
        private String trainUnit;
    }

    /**
     * Stores the file in {@code body} (null when the controller saw more than {@link #MAX_BODY_LENGTH}). A body that
     * does not read or keep the rules is a 400 {@code Invalid journey file}; a run already stored is a 409
     * {@code Already imported}; nothing is stored in either case.
     */
    @Transactional
    public Result importFile(String body) {
        JourneyFile file = reader.read(body);
        JourneyFileRules.validate(file);
        List<Segment> segments = segments(file.samples());
        List<Map<Integer, Modem>> modemsBySegment = segments.stream().map(JourneyImportService::modemsOf).toList();
        if (journeys.existsByRunStartedMs(file.runStartedMs())) {
            throw new ConflictException("Already imported");
        }
        Long deviceId = device(file.device());
        Instant now = clock.instant();
        List<Journey> made = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            made.add(journey(file, i, segments.get(i).trainUnit, deviceId, now));
        }
        journeys.saveAllAndFlush(made);
        List<JourneySample> sampleRows = new ArrayList<>();
        List<JourneyModem> modemRows = new ArrayList<>();
        List<JourneyModemSample> modemSampleRows = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Long journeyId = made.get(i).getId();
            for (Sample sample : segments.get(i).samples) {
                sampleRows.add(sampleRow(journeyId, sample));
                for (Modem modem : orEmpty(sample.modems())) {
                    modemSampleRows.add(modemSampleRow(journeyId, sample.round(), modem));
                }
            }
            modemsBySegment.get(i).forEach((index, first) -> modemRows.add(modemRow(journeyId, index, first)));
        }
        samples.saveAllAndFlush(sampleRows);
        modems.saveAllAndFlush(modemRows);
        modemSamples.saveAllAndFlush(modemSampleRows);
        return linked(made, segments);
    }

    /** The samples in order, cut where a given train unit differs from the journey's. */
    private static List<Segment> segments(List<Sample> all) {
        List<Segment> segments = new ArrayList<>();
        Segment current = new Segment();
        segments.add(current);
        for (Sample sample : all) {
            String unit = sample.trainUnit();
            if (unit != null && current.trainUnit == null) {
                current.trainUnit = unit;
            } else if (unit != null && !unit.equals(current.trainUnit)) {
                current = new Segment();
                current.trainUnit = unit;
                segments.add(current);
            }
            current.samples.add(sample);
        }
        return segments;
    }

    /**
     * A segment's modems by index (only {@code index}, {@code deviceType} and {@code deviceSubtype} are kept). The
     * type, and the subtype, is the first value any of the segment's samples gives; a missing value in a sample is
     * "not given" and changes nothing, and a later value that is given and differs is a 400.
     */
    private static Map<Integer, Modem> modemsOf(Segment segment) {
        Map<Integer, Modem> kinds = new LinkedHashMap<>();
        for (Sample sample : segment.samples) {
            for (Modem modem : orEmpty(sample.modems())) {
                Modem known = kinds.get(modem.index());
                kinds.put(modem.index(), new Modem(modem.index(),
                    known == null ? modem.deviceType() : firstGiven(known.deviceType(), modem.deviceType()),
                    known == null ? modem.deviceSubtype() : firstGiven(known.deviceSubtype(), modem.deviceSubtype()),
                    null, null, null, null, null, null));
            }
        }
        return kinds;
    }

    private static String firstGiven(String kept, String later) {
        if (later == null || kept == null) {
            return kept == null ? later : kept;
        }
        if (!kept.equals(later)) {
            throw new BadRequestException(JourneyFileRules.INVALID);
        }
        return kept;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private Long device(DeviceInfo info) {
        if (info == null) {
            return null;
        }
        return devices.findByModelAndCpuAndCoresAndThreadsAndMemoryBytes(info.model(), info.cpu(), info.cores(),
            info.threads(), info.memoryBytes()).orElseGet(() -> {
                Device device = new Device();
                device.setModel(info.model());
                device.setCpu(info.cpu());
                device.setCores(info.cores());
                device.setThreads(info.threads());
                device.setMemoryBytes(info.memoryBytes());
                return devices.save(device);
            }).getId();
    }

    private static Journey journey(JourneyFile file, int segment, String trainUnit, Long deviceId, Instant now) {
        Journey journey = new Journey();
        journey.setRunStartedMs(file.runStartedMs());
        journey.setSegment(segment);
        journey.setDeviceId(deviceId);
        journey.setOsVersion(file.osVersion());
        journey.setTravelClass(file.travelClass());
        journey.setTrainUnit(trainUnit);
        journey.setSourceFile(file.sourceFile());
        journey.setLoggerVersion(file.loggerVersion());
        journey.setImportedAt(now);
        return journey;
    }

    private static JourneySample sampleRow(Long journeyId, Sample s) {
        JourneySample row = new JourneySample();
        row.setJourneyId(journeyId);
        row.setRoundNo(s.round());
        row.setSampledAt(Instant.ofEpochMilli(s.at()));
        row.setFeedAt(s.feedAt() == null ? null : Instant.ofEpochSecond(s.feedAt()));
        row.setFixMode(s.fixMode());
        row.setFixAgeS(s.fixAgeS());
        row.setLatitude(s.latitude());
        row.setLongitude(s.longitude());
        row.setAltitudeM(s.altitudeM());
        row.setSpeedMps(s.speedMps());
        row.setCourseDeg(s.courseDeg());
        row.setSatellites(s.satellites());
        row.setOnline(s.online());
        row.setUsersOnline(s.usersOnline());
        row.setUsersTotal(s.usersTotal());
        row.setDownLimit(s.downLimit());
        row.setUpLimit(s.upLimit());
        row.setCapLevel(s.capLevel());
        row.setUserClass(s.userClass());
        row.setDataDownUsed(s.dataDownUsed());
        row.setDataUpUsed(s.dataUpUsed());
        row.setProbeMs(s.probeMs());
        row.setProbeCode(s.probeCode());
        row.setPowerSource(s.powerSource());
        row.setBatteryPercent(s.batteryPercent());
        row.setLowPower(s.lowPower());
        row.setCpuLimit(s.cpuLimit());
        row.setLoadAvg(s.loadAvg());
        row.setSwapMb(s.swapMb());
        row.setWifiRssi(s.wifiRssi());
        row.setWifiNoise(s.wifiNoise());
        row.setWifiRate(s.wifiRate());
        row.setWifiPhy(s.wifiPhy());
        row.setWifiChannel(s.wifiChannel());
        row.setWifiBand(s.wifiBand());
        row.setWifiWidth(s.wifiWidth());
        return row;
    }

    private static JourneyModem modemRow(Long journeyId, Integer index, Modem first) {
        JourneyModem row = new JourneyModem();
        row.setJourneyId(journeyId);
        row.setModemIndex(index);
        row.setDeviceType(first.deviceType());
        row.setDeviceSubtype(first.deviceSubtype());
        return row;
    }

    private static JourneyModemSample modemSampleRow(Long journeyId, Integer round, Modem modem) {
        JourneyModemSample row = new JourneyModemSample();
        row.setJourneyId(journeyId);
        row.setRoundNo(round);
        row.setModemIndex(modem.index());
        row.setDeviceState(modem.deviceState());
        row.setLinkState(modem.linkState());
        row.setRssi(modem.rssi());
        row.setTechnology(modem.technology());
        row.setOperatorId(modem.operatorId());
        row.setCellId(modem.cellId());
        return row;
    }

    /** Links each journey to the playback records that overlap it, and answers what was made. */
    private Result linked(List<Journey> made, List<Segment> segments) {
        List<Imported> answer = new ArrayList<>();
        List<JourneyPlayback> linkRows = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            Journey journey = made.get(i);
            List<Sample> inOrder = segments.get(i).samples;
            Instant from = Instant.ofEpochMilli(inOrder.getFirst().at());
            Instant to = Instant.ofEpochMilli(inOrder.getLast().at());
            List<Long> ids = playbacks.idsOverlapping(from.minus(LINK_MARGIN), to.plus(LINK_MARGIN),
                PageRequest.of(0, MAX_LINKS));
            for (Long id : ids) {
                JourneyPlayback link = new JourneyPlayback();
                link.setJourneyId(journey.getId());
                link.setPlaybackHealthId(id);
                linkRows.add(link);
            }
            answer.add(new Imported(journey.getId(), journey.getSegment(), journey.getTrainUnit(), inOrder.size(),
                from, to, ids.size()));
        }
        links.saveAllAndFlush(linkRows);
        return new Result(answer);
    }
}
