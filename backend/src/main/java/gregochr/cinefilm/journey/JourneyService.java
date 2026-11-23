package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists the stored journeys and deletes one (plan: {@code journey-data-plan.md}, decisions 3, 4 and 9). The list is
 * read in a fixed number of queries whatever the number of journeys.
 */
@Service
public class JourneyService {

    private final JourneyRepository journeys;
    private final JourneySampleRepository samples;
    private final JourneyPlaybackRepository links;
    private final DeviceRepository devices;
    private final TrainRunRepository runs;
    private final JourneyTrainService trains;

    /** Creates the service over the journey, device and train repositories and the train service. */
    public JourneyService(JourneyRepository journeys, JourneySampleRepository samples,
                          JourneyPlaybackRepository links, DeviceRepository devices, TrainRunRepository runs,
                          JourneyTrainService trains) {
        this.journeys = journeys;
        this.samples = samples;
        this.links = links;
        this.devices = devices;
        this.runs = runs;
        this.trains = trains;
    }

    /** The laptop that logged a journey. */
    public record DeviceView(String model, String cpu, int cores, int threads, long memoryBytes) {
    }

    /** The train a journey was on: its uid, its date as {@code YYYY-MM-DD} and its headcode. */
    public record TrainView(String uid, String date, String headcode) {
    }

    /** One journey as the list shows it. */
    public record Entry(Long id, long runStartedMs, int segment, String trainUnit, String travelClass,
                        String osVersion, int loggerVersion, String sourceFile, Instant importedAt,
                        DeviceView device, long samples, Instant from, Instant to, long linked, TrainView train) {
    }

    /** The answer of the list. */
    public record Listing(List<Entry> journeys) {
    }

    /**
     * Every journey, the newest run first and a run's segments in order. The transaction is repeatable-read so that
     * its five queries read the one snapshot of the first (a delete or an import under way is seen whole or not at
     * all), where each would otherwise read a moment of its own.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Listing list() {
        List<Journey> all = journeys.findAllByOrderByRunStartedMsDescSegmentAsc();
        Map<Long, SampleStats> stats = samples.statsOfEveryJourney().stream()
            .collect(Collectors.toMap(SampleStats::journeyId, Function.identity()));
        Map<Long, Long> linked = links.countsOfEveryJourney().stream()
            .collect(Collectors.toMap(LinkCount::journeyId, LinkCount::count));
        Map<Long, Device> deviceById = devices.findAllById(
                all.stream().map(Journey::getDeviceId).filter(Objects::nonNull).distinct().toList()).stream()
            .collect(Collectors.toMap(Device::getId, Function.identity()));
        Map<Long, TrainRun> runById = runs.findAllById(
                all.stream().map(Journey::getTrainRunId).filter(Objects::nonNull).distinct().toList()).stream()
            .collect(Collectors.toMap(TrainRun::getId, Function.identity()));
        return new Listing(all.stream().map(j -> entry(j, stats.get(j.getId()), linked.getOrDefault(j.getId(), 0L),
            deviceById.get(j.getDeviceId()), runById.get(j.getTrainRunId()))).toList());
    }

    /**
     * Deletes the journey (404 {@code No such journey} when there is none); its samples, modems, modem samples and
     * links go with it by the tables' own cascade. Its train's run is swept afterwards, and the sweep's statement
     * flushes first, so the journey's delete reaches the database before the sweep looks for a journey on the run.
     */
    @Transactional
    public void delete(Long journeyId) {
        Journey journey = journeys.findLockedById(journeyId)
            .orElseThrow(() -> new NotFoundException("No such journey"));
        journeys.delete(journey);
        if (journey.getTrainRunId() != null) {
            trains.sweep(journey.getTrainRunId());
        }
    }

    private static Entry entry(Journey j, SampleStats stats, long linked, Device device, TrainRun run) {
        return new Entry(j.getId(), j.getRunStartedMs(), j.getSegment(), j.getTrainUnit(), j.getTravelClass(),
            j.getOsVersion(), j.getLoggerVersion(), j.getSourceFile(), j.getImportedAt(),
            device == null ? null : new DeviceView(device.getModel(), device.getCpu(), device.getCores(),
                device.getThreads(), device.getMemoryBytes()),
            stats == null ? 0 : stats.count(), stats == null ? null : stats.earliest(),
            stats == null ? null : stats.latest(), linked,
            run == null ? null : new TrainView(run.getTimetableUid(), run.getRunDate().toString(),
                run.getHeadcode()));
    }
}
