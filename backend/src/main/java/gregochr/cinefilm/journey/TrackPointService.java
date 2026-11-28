package gregochr.cinefilm.journey;

import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.health.HealthDtos.HealthReport;
import gregochr.cinefilm.health.PlaybackHealth;
import gregochr.cinefilm.health.PlaybackHealthRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds a journey's track points and the state of each (plan: {@code train-lookahead-plan.md}, decision 5). A point
 * is a round with a position. Its minute is the minute row of a linked viewing that contains the round's time (the
 * later-started part's where two parts do); that minute's own good or bad verdict stands, and with none the logger's
 * internet check decides. Positions never leave the server: this only reads.
 */
@Service
public class TrackPointService {

    private static final Logger LOG = LoggerFactory.getLogger(TrackPointService.class);

    /** The slowest internet check, in ms, that still counts as good. */
    static final int SLOW_CHECK_MS = 1000;

    private final JourneySampleRepository samples;
    private final JourneyPlaybackRepository links;
    private final PlaybackHealthRepository health;
    private final EditionRepository editions;
    private final JsonMapper mapper;

    /** Creates the service over the journey, health and edition repositories and the JSON mapper. */
    public TrackPointService(JourneySampleRepository samples, JourneyPlaybackRepository links,
                             PlaybackHealthRepository health, EditionRepository editions, JsonMapper mapper) {
        this.samples = samples;
        this.links = links;
        this.health = health;
        this.editions = editions;
        this.mapper = mapper;
    }

    /** The journey's points in round order, empty when no round has a position. */
    @Transactional(readOnly = true)
    public List<TrackPoint> pointsOf(Long journeyId) {
        List<JourneySample> rounds = samples.findByJourneyIdOrderByRoundNo(journeyId);
        List<JourneySample> positioned = rounds.stream()
            .filter(s -> s.getLatitude() != null && s.getLongitude() != null).toList();
        if (positioned.isEmpty()) {
            return List.of();
        }
        boolean anyCheck = rounds.stream().anyMatch(s -> s.getProbeMs() != null);
        List<PartMinutes> parts = partsOf(journeyId);
        Instant start = positioned.get(0).getSampledAt();
        return positioned.stream().map(s -> new TrackPoint(s.getLatitude(), s.getLongitude(), s.getCourseDeg(),
            s.getSpeedMps(), Duration.between(start, s.getSampledAt()).toSeconds(),
            stateOf(s, parts, anyCheck))).toList();
    }

    private PointState stateOf(JourneySample round, List<PartMinutes> parts, boolean anyCheck) {
        PartMinutes latest = null;
        List<Long> row = null;
        for (PartMinutes part : parts) {
            List<Long> candidate = part.rowAt(round.getSampledAt());
            if (candidate != null && (latest == null || part.startedAt().isAfter(latest.startedAt()))) {
                latest = part;
                row = candidate;
            }
        }
        PointState minute = latest == null ? PointState.UNKNOWN : latest.verdict(row);
        return minute != PointState.UNKNOWN ? minute : checkState(round.getProbeMs(), anyCheck);
    }

    private static PointState checkState(Integer probeMs, boolean anyCheck) {
        if (probeMs == null) {
            return anyCheck ? PointState.BAD : PointState.UNKNOWN;
        }
        return probeMs <= SLOW_CHECK_MS ? PointState.GOOD : PointState.BAD;
    }

    /** The minute rows of the playbacks linked to the journey; a viewing that is gone or unreadable adds none. */
    private List<PartMinutes> partsOf(Long journeyId) {
        List<Long> ids = links.findByJourneyId(journeyId).stream().map(JourneyPlayback::getPlaybackHealthId).toList();
        List<PlaybackHealth> stored = health.findAllById(ids);
        Map<Long, Edition> editionById = editions.findAllById(
            stored.stream().map(PlaybackHealth::getEditionId).distinct().toList()).stream()
            .collect(Collectors.toMap(Edition::getId, Function.identity()));
        List<PartMinutes> parts = new ArrayList<>();
        for (PlaybackHealth row : stored) {
            HealthReport report = read(row);
            if (report != null) {
                parts.add(new PartMinutes(row.getStartedAt(), !Long.valueOf(0).equals(report.seekCount()),
                    report.endPositionMs(), KnownRuntime.knownRuntimeMs(editionById.get(row.getEditionId())),
                    rowsOf(report)));
            }
        }
        return parts;
    }

    private static NavigableMap<Long, List<Long>> rowsOf(HealthReport report) {
        NavigableMap<Long, List<Long>> rows = new TreeMap<>();
        for (List<Long> row : Optional.ofNullable(report.minutes()).orElse(List.of())) {
            rows.put(row.get(0), row);
        }
        return rows;
    }

    private HealthReport read(PlaybackHealth row) {
        try {
            return mapper.readValue(row.getSummary(), HealthReport.class);
        } catch (JacksonException e) {
            LOG.warn("Playback health row {} has an unreadable summary", row.getId());
            return null;
        }
    }
}
