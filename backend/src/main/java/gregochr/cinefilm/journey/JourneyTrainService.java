package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.journey.TrainTimes.Span;
import gregochr.cinefilm.journey.TrainTimes.Times;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gives a journey its train and takes it away (plan: {@code journey-data-plan.md}, decision 9). Both begin by locking
 * the journey, so two requests about one journey run one after the other. A run (one service on one date) is stored
 * once however many journeys point at it, and {@link #sweep} is the one place a run is deleted (it locks the run
 * first, so two requests letting go of one run at once run one after the other and the second deletes it). Locks are
 * taken journey first, then run.
 *
 * <p>Both bulk statements ({@code deleteAllOfRun}, {@code deleteIfUnused}) flush first and clear the persistence
 * context afterwards, so an entity loaded before one is detached after it: every changed entity is written with its
 * repository's {@code save}, never left to dirty checking.
 */
@Service
public class JourneyTrainService {

    /** Longest request body accepted, in characters. */
    static final int MAX_BODY_LENGTH = 200_000;

    /** How far either side of the journey's samples the train's times may lie and still overlap it. */
    static final Duration MARGIN = Duration.ofMinutes(30);

    private final JourneyRepository journeys;
    private final JourneySampleRepository samples;
    private final TrainRunRepository runs;
    private final TrainRunCallRepository calls;
    private final TrainFileReader reader;
    private final Clock clock;

    /** Creates the service over the journey and train repositories, the application mapper and clock. */
    public JourneyTrainService(JourneyRepository journeys, JourneySampleRepository samples, TrainRunRepository runs,
                               TrainRunCallRepository calls, JsonMapper mapper, Clock clock) {
        this.journeys = journeys;
        this.samples = samples;
        this.runs = runs;
        this.calls = calls;
        this.reader = new TrainFileReader(mapper);
        this.clock = clock;
    }

    /** What was stored: the file's uid, date and headcode as it gave them, and the number of calls. */
    public record Stored(String uid, String date, String headcode, int calls) {
    }

    /**
     * Gives the journey the train in {@code body} (null when the controller saw more than {@link #MAX_BODY_LENGTH}).
     * A body that does not read or keep the rules is a 400 {@code Invalid train file}; a train whose times lie
     * outside the journey's, half an hour allowed either side, is a 400 {@code The train did not run during this
     * journey}; a run already stored with other end points is a 409 {@code This train is stored with other end
     * points}. Nothing is changed in either case.
     */
    @Transactional
    public Stored give(Long journeyId, String body) {
        Journey journey = locked(journeyId);
        TrainFile file = reader.read(body);
        TrainFileRules.validate(file);
        List<Times> times = TrainTimes.instants(file);
        Span span = TrainTimes.span(times);
        SampleSpan logged = samples.spanOf(journeyId);
        if (logged.earliest() == null || span.earliest().minus(MARGIN).isAfter(logged.latest())
            || span.latest().plus(MARGIN).isBefore(logged.earliest())) {
            throw new BadRequestException("The train did not run during this journey");
        }
        Long runId = store(file, times);
        Long previous = journey.getTrainRunId();
        journey.setTrainRunId(runId);
        journeys.save(journey);
        if (previous != null) {
            // Whichever run it was: one still in use (even the run just given) is not deleted, and this transaction
            // already holds its lock.
            sweep(previous);
        }
        return new Stored(file.uid(), file.date(), file.headcode(), file.calls().size());
    }

    /** Takes the journey's train away (nothing changes for a journey with none) and sweeps the run it had. */
    @Transactional
    public void takeAway(Long journeyId) {
        Journey journey = locked(journeyId);
        Long runId = journey.getTrainRunId();
        if (runId != null) {
            journey.setTrainRunId(null);
            journeys.save(journey);
            sweep(runId);
        }
    }

    /**
     * Deletes the run unless a journey still points at it; its calls go with it. The one place a run is deleted. The
     * run is locked first: two requests letting go of one run at once (each holding the lock of its own journey)
     * would otherwise each still see the other's journey on it, under READ COMMITTED, and leave it behind. Now the
     * second waits for the first to commit, and its delete statement, which starts after that, sees the first one's
     * change and deletes the run. A run already gone is not found, and the statement deletes nothing. The locks are
     * always taken journey first, then run, as in {@link #give}.
     */
    @Transactional
    public void sweep(Long runId) {
        runs.findLockedById(runId);
        runs.deleteIfUnused(runId);
    }

    private Journey locked(Long journeyId) {
        return journeys.findLockedById(journeyId).orElseThrow(() -> new NotFoundException("No such journey"));
    }

    /** The id of the run of the file, made or replaced, with its calls. */
    private Long store(TrainFile file, List<Times> times) {
        LocalDate date = LocalDate.parse(file.date());
        Optional<TrainRun> found = runs.findByTimetableUidAndRunDate(file.uid(), date);
        TrainRun run;
        if (found.isEmpty()) {
            run = new TrainRun();
            run.setTimetableUid(file.uid());
            run.setRunDate(date);
            run.setOperator(file.operator());
            run.setSource(file.source());
        } else {
            run = found.get();
            List<TrainRunCall> stored = calls.findByTrainRunIdOrderBySeq(run.getId());
            if (!stored.getFirst().getLocation().equals(times.getFirst().location())
                || !stored.getLast().getLocation().equals(times.getLast().location())) {
                throw new ConflictException("This train is stored with other end points");
            }
            if (file.operator() != null) {
                run.setOperator(file.operator());
            }
            if (file.source() != null) {
                run.setSource(file.source());
            }
        }
        run.setHeadcode(file.headcode());
        run.setImportedAt(clock.instant());
        Long runId = runs.save(run).getId();
        if (found.isPresent()) {
            calls.deleteAllOfRun(runId);
        }
        calls.saveAll(callRows(runId, times));
        return runId;
    }

    private static List<TrainRunCall> callRows(Long runId, List<Times> times) {
        List<TrainRunCall> rows = new ArrayList<>();
        for (int seq = 0; seq < times.size(); seq++) {
            Times at = times.get(seq);
            TrainRunCall row = new TrainRunCall();
            row.setTrainRunId(runId);
            row.setSeq(seq);
            row.setLocation(at.location());
            row.setPlannedArrival(at.plannedArrival());
            row.setPlannedDeparture(at.plannedDeparture());
            row.setPlannedPass(at.plannedPass());
            row.setActualArrival(at.actualArrival());
            row.setActualDeparture(at.actualDeparture());
            row.setActualPass(at.actualPass());
            rows.add(row);
        }
        return rows;
    }
}
