package gregochr.cinefilm.health;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.health.HealthDtos.ChartList;
import gregochr.cinefilm.health.HealthDtos.ChartRow;
import gregochr.cinefilm.health.HealthDtos.HealthReport;
import gregochr.cinefilm.health.HealthDtos.PlaybackList;
import gregochr.cinefilm.health.HealthDtos.PlaybackPage;
import gregochr.cinefilm.health.HealthDtos.PlaybackRow;
import jakarta.persistence.criteria.Predicate;
import gregochr.cinefilm.health.HealthDtos.PlaybackCounts;
import gregochr.cinefilm.health.HealthDtos.PlayingNow;
import gregochr.cinefilm.transcode.Ladder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    /** Shortest watched time, in ms, that counts as a film session: shorter ones are tests, skips and misclicks. */
    public static final long FILM_SESSION_MIN_MS = 180_000;

    /** How far back the Admin summary counts film sessions, by {@code startedAt}. */
    static final Duration SUMMARY_WINDOW = Duration.ofDays(30);

    /** How recently a viewing must have reported to count as playing now. */
    static final Duration PLAYING_WINDOW = Duration.ofMinutes(2);

    /** Most viewings "playing now" lists. */
    static final int PLAYING_NOW_MAX = 10;

    /**
     * Most rows read from the two-minute window before they are reduced to one per edition. A safety bound only: the
     * window holds a few rows in practice, but a player that reloads in a loop must not make the read unbounded, and
     * the cap of {@link #PLAYING_NOW_MAX} applies after the reduction so reloads of one edition cannot hide another.
     */
    static final int PLAYING_NOW_WINDOW_ROWS = 200;

    /** Quality ids by the rung code of a {@code minutes} row (code 1 is the first); code 0 means unknown. */
    private static final List<String> RUNG_IDS = List.of(Ladder.ORIGINAL, Ladder.Q_1080, Ladder.Q_720, Ladder.Q_480,
        Ladder.Q_360, Ladder.Q_240);

    /** Index of "rung played" in a {@code minutes} row. */
    private static final int RUNG_PLAYED = 2;

    /** Most rows one page of the filtered list holds. */
    static final int MAX_PAGE_SIZE = 100;

    /** Rows in a page when the request names none. */
    static final int DEFAULT_PAGE_SIZE = 20;

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
            repository.replaceIfNewer(playbackId, report.seq().intValue(), parsed.summary(), now,
                HealthFigures.of(report));
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

    /**
     * One page of viewings, newest {@code startedAt} first (the higher id first on a tie), each without the
     * per-minute track, and the number of all that match. Every filter is optional: {@code since} keeps the viewings
     * that started at or after it, {@code minWatchedMs} those that played at least that long, {@code problems}
     * those whose {@code hasProblem} equals it. {@code page} counts from 0 (default 0) and {@code size} is 1 to
     * {@link #MAX_PAGE_SIZE} (default {@link #DEFAULT_PAGE_SIZE}), and {@code page * size} may not pass
     * {@link Integer#MAX_VALUE} (the offset asked of the database); anything else is a 400. A stored report that
     * cannot be read is left out of the page but still counted in the total.
     */
    @Transactional(readOnly = true)
    public PlaybackPage query(Instant since, Long minWatchedMs, Boolean problems, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE
            || (long) pageNumber * pageSize > Integer.MAX_VALUE
            || (minWatchedMs != null && minWatchedMs < 0)) {
            throw new BadRequestException("Invalid request");
        }
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "startedAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Page<PlaybackHealth> found = repository.findAll(matching(since, minWatchedMs, problems),
            PageRequest.of(pageNumber, pageSize, newestFirst));
        List<PlaybackRow> rows = new ArrayList<>();
        for (PlaybackHealth stored : found.getContent()) {
            ObjectNode report = readable(stored);
            if (report != null) {
                report.remove("minutes");
                rows.add(row(stored, report));
            }
        }
        return new PlaybackPage(rows, found.getTotalElements());
    }

    /**
     * Every film session ({@code watchedMs} at least {@link #FILM_SESSION_MIN_MS}) that started at or after
     * {@code since} (all of them when null), oldest first so the newest comes last, with only the figures the chart
     * draws. The table is capped, so the answer is small and is not paged.
     */
    @Transactional(readOnly = true)
    public ChartList chart(Instant since) {
        Sort oldestFirst = Sort.by(Sort.Direction.ASC, "startedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        List<ChartRow> rows = new ArrayList<>();
        for (PlaybackHealth stored : repository.findAll(matching(since, FILM_SESSION_MIN_MS, null), oldestFirst)) {
            HealthReport report = typed(stored);
            rows.add(new ChartRow(stored.getPlaybackId(), stored.getFilmId(), stored.getFilmTitle(),
                stored.getStartedAt(), stored.getWatchedMs(), stored.getStallCount(), stored.getStallMs(),
                stored.getStartupMs(), stored.getEnvironment(), report == null ? null : report.rungMs(),
                report == null || report.client() == null ? null : report.client().userAgent()));
        }
        return new ChartList(rows);
    }

    /**
     * The film sessions of the last 30 days (by {@code startedAt}, the boundary included): those that watched at least
     * {@link #FILM_SESSION_MIN_MS}, and how many of them had no problem. Two COUNT queries, no rows loaded.
     */
    @Transactional(readOnly = true)
    public PlaybackCounts playbackCounts() {
        Instant since = clock.instant().minus(SUMMARY_WINDOW);
        return new PlaybackCounts(repository.countFilmSessions(since, FILM_SESSION_MIN_MS),
            repository.countSmoothFilmSessions(since, FILM_SESSION_MIN_MS));
    }

    /**
     * The viewings that reported in the last two minutes (the boundary included), newest report first, at most
     * {@link #PLAYING_NOW_MAX}; direct play and HLS alike. Only the newest report of each edition lists, so a player
     * that reloaded (a new playback id for the same edition) shows once. A report carries no end marker (the player's
     * exit report is the same snapshot), so a viewing that ended stays listed until two minutes after its last
     * report. The quality is the rung played in the report's last minute, else the quality the viewer chose; a
     * stored report that cannot be read still lists, with no device, no touch hint and no quality.
     */
    @Transactional(readOnly = true)
    public List<PlayingNow> playingNow() {
        Instant since = clock.instant().minus(PLAYING_WINDOW);
        List<PlayingNow> playing = new ArrayList<>();
        Set<Long> editions = new HashSet<>();
        for (PlaybackHealth stored : repository.updatedSince(since, PageRequest.ofSize(PLAYING_NOW_WINDOW_ROWS))) {
            if (playing.size() == PLAYING_NOW_MAX) {
                break;
            }
            if (!editions.add(stored.getEditionId())) {
                continue;
            }
            HealthReport report = typed(stored);
            playing.add(new PlayingNow(stored.getPlaybackId(), stored.getEditionId(), stored.getFilmTitle(),
                report == null || report.client() == null ? null : report.client().userAgent(),
                report == null || report.client() == null ? null : report.client().touch(),
                stored.getEnvironment(), report == null ? null : qualityOf(report)));
        }
        return playing;
    }

    private static String qualityOf(HealthReport report) {
        // A stored report passed HealthRules: minutes is present, every row has the rung played, codes are 0 to 6.
        List<List<Long>> minutes = report.minutes();
        if (!minutes.isEmpty()) {
            long code = minutes.get(minutes.size() - 1).get(RUNG_PLAYED);
            if (code >= 1) {
                return RUNG_IDS.get((int) code - 1);
            }
        }
        return report.quality();
    }

    private static Specification<PlaybackHealth> matching(Instant since, Long minWatchedMs, Boolean problems) {
        return (root, query, builder) -> {
            List<Predicate> all = new ArrayList<>();
            if (since != null) {
                all.add(builder.greaterThanOrEqualTo(root.<Instant>get("startedAt"), since));
            }
            if (minWatchedMs != null) {
                all.add(builder.greaterThanOrEqualTo(root.<Long>get("watchedMs"), minWatchedMs));
            }
            if (problems != null) {
                all.add(builder.equal(root.<Boolean>get("hasProblem"), problems));
            }
            return builder.and(all.toArray(Predicate[]::new));
        };
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
        created.setFigures(HealthFigures.of(report));
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

    /** The stored report as a typed report, or null when it cannot be read. */
    private HealthReport typed(PlaybackHealth stored) {
        try {
            return mapper.readValue(stored.getSummary(), HealthReport.class);
        } catch (JacksonException e) {
            LOG.warn("Playback health row {} has an unreadable summary", stored.getId());
            return null;
        }
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
