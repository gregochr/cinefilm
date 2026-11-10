package gregochr.cinefilm.health;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.health.HealthDtos.HealthReport;
import gregochr.cinefilm.health.HealthDtos.PlaybackList;
import gregochr.cinefilm.health.HealthDtos.PlaybackRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores and serves the health reports of viewings. A report is client-supplied: it is read as text, bounded, parsed
 * into a typed report, checked by {@link HealthRules} and stored as the server's own serialisation, never as the
 * client's text.
 */
@Service
public class PlaybackHealthService {

    /** Longest request body accepted, in characters. */
    static final int MAX_BODY_LENGTH = 20_000;

    /**
     * How many records are kept; the ones updated longest ago go first. The trim runs after each creation and
     * removes whatever is over the cap as that transaction sees the table. Records created at the same moment do
     * not see each other's rows, so they can leave the table over the cap by their number until the next creation
     * trims it again: a bound on growth, not an exact count.
     */
    static final int MAX_ROWS = 2_000;

    private static final Logger LOG = LoggerFactory.getLogger(PlaybackHealthService.class);
    private static final Pattern PLAYBACK_ID =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final PlaybackHealthRepository repository;
    private final EditionRepository editions;
    private final JsonMapper mapper;
    private final Clock clock;

    /** Creates the service over its repositories, the application's JSON mapper and the application clock. */
    public PlaybackHealthService(PlaybackHealthRepository repository, EditionRepository editions, JsonMapper mapper,
                                 Clock clock) {
        this.repository = repository;
        this.editions = editions;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Records a report: the first of a viewing creates it (and trims the table towards its cap, see
     * {@link #MAX_ROWS}), a later one replaces it only when its {@code seq} is higher, and an equal or lower one is
     * left out without an error.
     */
    @Transactional
    public void report(String playbackId, String body) {
        if (playbackId == null || !PLAYBACK_ID.matcher(playbackId).matches()) {
            throw invalid();
        }
        ParsedReport parsed = parse(body);
        HealthReport report = parsed.report();
        Instant now = clock.instant();
        Optional<PlaybackHealth> existing = repository.findByPlaybackId(playbackId);
        if (existing.isEmpty()) {
            create(playbackId, report, parsed.summary(), now);
        } else if (existing.get().getEditionId().equals(report.editionId())) {
            // 0 rows changed means the stored report is as new or newer: not an error.
            repository.replaceIfNewer(playbackId, report.seq().intValue(), parsed.summary(), now);
        } else {
            throw invalid();
        }
    }

    /** The 20 most recently updated viewings, newest first, without the per-minute track. */
    @Transactional(readOnly = true)
    public PlaybackList recent() {
        List<PlaybackRow> rows = new ArrayList<>();
        for (PlaybackHealth stored : repository.findTop20ByOrderByUpdatedAtDescIdDesc()) {
            ObjectNode report = readable(stored);
            if (report != null) {
                report.remove("minutes");
                rows.add(row(stored, report));
            }
        }
        return new PlaybackList(rows);
    }

    /** One viewing with its whole report; 404 when the id is not one, has no record or its report cannot be read. */
    @Transactional(readOnly = true)
    public PlaybackRow one(String playbackId) {
        if (playbackId == null || !PLAYBACK_ID.matcher(playbackId).matches()) {
            throw notFound();
        }
        PlaybackHealth stored = repository.findByPlaybackId(playbackId).orElseThrow(PlaybackHealthService::notFound);
        ObjectNode report = readable(stored);
        if (report == null) {
            throw notFound();
        }
        return row(stored, report);
    }

    private void create(String playbackId, HealthReport report, String summary, Instant now) {
        Edition edition = editions.findById(report.editionId()).orElseThrow(() -> new NotFoundException(
            "No such edition"));
        PlaybackHealth created = new PlaybackHealth();
        created.setPlaybackId(playbackId);
        created.setEditionId(edition.getId());
        created.setFilmId(edition.getFilm().getId());
        created.setFilmTitle(edition.getFilm().getTitle());
        created.setStartedAt(now.minusMillis(report.elapsedMs()));
        created.setUpdatedAt(now);
        created.setSeq(report.seq().intValue());
        created.setSummary(summary);
        repository.save(created);
        long excess = repository.count() - MAX_ROWS;
        if (excess > 0) {
            repository.deleteAllByIdInBatch(repository.oldestIds(PageRequest.of(0, (int) excess)));
        }
    }

    private record ParsedReport(HealthReport report, String summary) {
    }

    /** Parses and checks a body; the summary is the report serialised again, at most 16,000 characters. */
    private ParsedReport parse(String body) {
        if (body == null || body.length() > MAX_BODY_LENGTH) {
            throw invalid();
        }
        HealthReport report;
        String summary;
        try {
            report = mapper.readValue(body, HealthReport.class);
            HealthRules.validate(report);
            summary = mapper.writeValueAsString(report);
        } catch (JacksonException e) {
            throw invalid();
        }
        if (summary.length() > PlaybackHealth.MAX_SUMMARY_LENGTH) {
            throw invalid();
        }
        return new ParsedReport(report, summary);
    }

    /** The stored report as a JSON object, or null (and a warning naming only the row) when it cannot be read. */
    private ObjectNode readable(PlaybackHealth stored) {
        try {
            if (mapper.readTree(stored.getSummary()) instanceof ObjectNode object) {
                return object;
            }
        } catch (JacksonException e) {
            // Fall through to the warning below: the row is left out, never a 500.
        }
        LOG.warn("Playback health row {} has an unreadable summary", stored.getId());
        return null;
    }

    private static PlaybackRow row(PlaybackHealth stored, JsonNode report) {
        return new PlaybackRow(stored.getPlaybackId(), stored.getFilmId(), stored.getFilmTitle(),
            stored.getEditionId(), stored.getStartedAt(), stored.getUpdatedAt(), report);
    }

    private static BadRequestException invalid() {
        return new BadRequestException(HealthRules.INVALID);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("No such playback");
    }
}
