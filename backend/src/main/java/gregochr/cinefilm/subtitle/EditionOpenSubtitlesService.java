package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.config.ServiceUnavailableException;
import gregochr.cinefilm.config.UnprocessableException;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesApi;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesBudget;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesRanking;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReference;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesReferenceRepository;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesResult;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesSearch;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadDto;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadRequest;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.ResultDto;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.SearchDto;
import gregochr.cinefilm.subtitle.ForcedDownloadService.ChosenOutcome;
import gregochr.cinefilm.subtitle.PickerSearches.Offered;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The Admin picker's OpenSubtitles routes for one edition: a live search ranked for the edition's name, and the
 * download of one file the owner picked from it. The download goes through the same path as the nightly one
 * ({@link ReferenceFetcher}: the day's budget, {@link DailyDownloadLimit}, {@link DownloadShares}; and
 * {@link ForcedDownloadService}: the cap of {@value DownloadPlan#MAX_DOWNLOADS} files per edition, the removed files
 * that stay blocked, the library lock) but takes only a file that a search this server ran for the same edition and
 * kind in the last {@value PickerSearches#TTL_MINUTES} minutes offered ({@link PickerSearches}). The picked file is
 * marked as the owner's pick ({@code ownerChosenAt}, see {@link ForcedDownloadService#addChosen}). Nothing is held in
 * a transaction while OpenSubtitles is asked.
 *
 * <p>Two downloads for one edition and kind cannot overlap: the second is refused at once (409) while the first is
 * running, and once it has finished the cap and the "already downloaded" check, made again under the library lock, stop
 * a repeat.
 */
@Service
public class EditionOpenSubtitlesService {

    private static final Logger LOG = LoggerFactory.getLogger(EditionOpenSubtitlesService.class);

    private static final String BAD_KIND = "The kind must be full or forced";
    private static final String NO_IMDB = "This film has no IMDb id, so OpenSubtitles cannot be searched. "
        + "Fix its match first.";
    private static final String NO_FILE = "This edition has no original file to attach subtitles to.";

    private static final Map<ChosenOutcome, String> REFUSALS = Map.of(
        ChosenOutcome.CHANGED, "This film or edition changed while you were choosing. Search again.",
        ChosenOutcome.NOT_WANTED, "You marked this film as not needing these subtitles. Change that first.",
        ChosenOutcome.BLOCKED, "You removed that file from this edition earlier, so it is never downloaded for it "
            + "again.",
        ChosenOutcome.ALREADY_DOWNLOADED, "That file is already this edition's download.",
        ChosenOutcome.NO_FILE, NO_FILE,
        ChosenOutcome.CAP_REACHED, "This edition has already used its " + DownloadPlan.MAX_DOWNLOADS
            + " OpenSubtitles files (removed ones count), so no more can be downloaded for it.");

    private final OpenSubtitlesApi api;
    private final EditionRepository editions;
    private final ForcedDownloadService downloads;
    private final DailyDownloadLimit limits;
    private final LibraryLock lock;
    private final PickerSearches searches;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final ReferenceFetcher fetcher;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** Retimes a reference as it is stored while the retime setting is on; required. */
    @Autowired
    void retimeOnArrival(RetimeOnArrival arrival) {
        fetcher.onArrival(arrival::referenceArrived);
    }

    /** Creates the service. */
    public EditionOpenSubtitlesService(OpenSubtitlesApi api, EditionRepository editions,
                                       ForcedDownloadService downloads, OpenSubtitlesBudget budget,
                                       OpenSubtitlesReferenceRepository references, DailyDownloadLimit limits,
                                       LibraryLock lock, PickerSearches searches, Clock clock,
                                       ApplicationEventPublisher events) {
        this.api = api;
        this.editions = editions;
        this.downloads = downloads;
        this.limits = limits;
        this.lock = lock;
        this.searches = searches;
        this.clock = clock;
        this.events = events;
        this.fetcher = new ReferenceFetcher(api, budget, references, clock, limits);
    }

    /**
     * Searches OpenSubtitles for the edition's film and ranks the single-file results for the edition's name
     * ({@link OpenSubtitlesRanking#rankFor} for forced, {@link OpenSubtitlesRanking#rankForFull} for full). A forced
     * search returns foreign-parts-only files only, a full one everything else. What it returned is remembered for
     * {@value PickerSearches#TTL_MINUTES} minutes: only those files can be downloaded. A search is not charged.
     *
     * @param editionId the edition
     * @param kind {@code full} or {@code forced}
     * @return the ranked results and how many more files the edition may be given
     * @throws BadRequestException when {@code kind} is missing or neither, or the film has no usable IMDb id
     * @throws NotFoundException when there is no such edition
     * @throws ConflictException when the edition has no original file (only premade renditions) to attach subtitles to
     * @throws ServiceUnavailableException when OpenSubtitles is not configured
     * @throws gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException when OpenSubtitles cannot answer
     */
    public SearchDto search(Long editionId, String kind) {
        SubtitleKind parsed = kindOf(kind);
        Edition edition = edition(editionId);
        String imdbId = edition.getFilm().getImdbId();
        if (imdbId == null || imdbId.isBlank()) {
            throw new BadRequestException(NO_IMDB);
        }
        if (!downloads.hasOriginalFile(edition.getFilm().getId(), editionId)) {
            throw new ConflictException(NO_FILE);
        }
        if (!api.enabled()) {
            throw new ServiceUnavailableException("OpenSubtitles is not set up");
        }
        OpenSubtitlesSearch found;
        try {
            found = parsed == SubtitleKind.FULL ? api.searchFull(imdbId) : api.searchForced(imdbId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(NO_IMDB);
        }
        List<OpenSubtitlesResult> singles = found.results().stream().filter(OpenSubtitlesResult::isSingleFile)
            .toList();
        List<OpenSubtitlesResult> ranked = parsed == SubtitleKind.FULL
            ? OpenSubtitlesRanking.rankForFull(singles, edition.getName())
            : OpenSubtitlesRanking.rankFor(singles, edition.getName());
        searches.put(editionId, parsed, imdbId, ranked);
        int left = downloads.downloadsLeft(edition.getFilm().getId(), editionId, parsed, imdbId);
        List<ResultDto> shown = ranked.stream().map(EditionOpenSubtitlesService::dto).toList();
        return new SearchDto(editionId, name(parsed), imdbId, left, shown);
    }

    /**
     * Downloads a file the owner picked and makes it the edition's download, pinned.
     *
     * @param editionId the edition
     * @param request the file id and kind
     * @return what was downloaded and how many more files the edition may be given
     * @throws BadRequestException when the kind or file id is missing or invalid, or the file was not in a search for
     *     this edition and kind in the last {@value PickerSearches#TTL_MINUTES} minutes
     * @throws UnprocessableException when the file was fetched (that may have used one of today's downloads; a stored
     *     copy is reused free) but is not a usable subtitle of the kind
     * @throws NotFoundException when there is no such edition
     * @throws ConflictException when a scan or an Admin change is running, another download for the edition and kind
     *     is, the film changed, the edition has no original file, the owner marked it as not needing this kind, the
     *     file was removed or is already the edition's, or the edition has used its
     *     {@value DownloadPlan#MAX_DOWNLOADS} files
     * @throws ServiceUnavailableException when OpenSubtitles downloads are not configured
     * @throws OpenSubtitlesQuotaException when the day's allowance is used up
     * @throws gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException when the download was charged but the file
     *     could not be fetched
     * @throws gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException when OpenSubtitles cannot answer
     */
    public DownloadDto download(Long editionId, DownloadRequest request) {
        if (request == null) {
            throw new BadRequestException(BAD_KIND);
        }
        SubtitleKind kind = kindOf(request.kind());
        if (request.fileId() == null || request.fileId() <= 0) {
            throw new BadRequestException("A file id is required");
        }
        long fileId = request.fileId();
        Edition edition = edition(editionId);
        if (!api.downloadsEnabled()) {
            throw new ServiceUnavailableException("OpenSubtitles downloads are not set up");
        }
        Offered offered = searches.find(editionId, kind, fileId).orElseThrow(() -> new BadRequestException(
            "That file was not in a recent search for this edition. Search again."));
        String key = editionId + ":" + kind;
        if (!running.add(key)) {
            throw new ConflictException("A download for this edition is already running");
        }
        try {
            return downloadOne(edition, kind, offered);
        } finally {
            running.remove(key);
        }
    }

    private DownloadDto downloadOne(Edition edition, SubtitleKind kind, Offered offered) {
        Long editionId = edition.getId();
        Long filmId = edition.getFilm().getId();
        String imdbId = offered.imdbId();
        OpenSubtitlesResult result = offered.result();
        // A scan or an Admin change that holds the lock would refuse the add at the end: say so before a download is
        // spent. The add checks again under the lock.
        if (lock.holder() != null) {
            throw new ConflictException(lock.busyText());
        }
        downloads.refusal(filmId, editionId, kind, imdbId, result.fileId())
            .ifPresent(this::refuse);
        OpenSubtitlesReference reference;
        try {
            reference = fetcher.fetch(filmId, imdbId, result, DownloadShares.forMissing(limits.today()));
        } catch (ReferenceFetcher.Stop e) {
            throw new OpenSubtitlesQuotaException(null);
        }
        Instant now = clock.instant();
        ChosenOutcome outcome = downloads.addChosen(filmId, editionId, kind, reference, result.fps(), now);
        if (outcome == ChosenOutcome.UNUSABLE) {
            throw new UnprocessableException(unusable(reference, kind));
        }
        if (outcome != ChosenOutcome.ADDED) {
            refuse(outcome);
        }
        announce();
        return new DownloadDto(editionId, name(kind), result.fileId(), result.release(),
            downloads.downloadsLeft(filmId, editionId, kind, imdbId));
    }

    /** Throws the error that says why a pick cannot be made; only refusals come here. */
    private void refuse(ChosenOutcome outcome) {
        throw new ConflictException(outcome == ChosenOutcome.BUSY ? lock.busyText() : REFUSALS.get(outcome));
    }

    private static String unusable(OpenSubtitlesReference reference, SubtitleKind kind) {
        if (!reference.isUsable()) {
            return "That subtitle file could not be read.";
        }
        return kind == SubtitleKind.FULL ? "That file is too short to be a full subtitle."
            : "That file is a full subtitle, not a forced one.";
    }

    /** Says a download was added, so its timing is fitted; a listener's failure does not undo the download. */
    private void announce() {
        try {
            events.publishEvent(new SubtitleDownloadsFinished());
        } catch (RuntimeException e) {
            LOG.warn("A listener of the subtitle downloads failed: {}", e.getClass().getSimpleName());
        }
    }

    private Edition edition(Long editionId) {
        return editions.findWithFilmById(editionId).orElseThrow(() -> new NotFoundException("No such edition"));
    }

    private static SubtitleKind kindOf(String kind) {
        if ("full".equals(kind)) {
            return SubtitleKind.FULL;
        }
        if ("forced".equals(kind)) {
            return SubtitleKind.FORCED;
        }
        throw new BadRequestException(BAD_KIND);
    }

    private static String name(SubtitleKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static ResultDto dto(OpenSubtitlesResult r) {
        return new ResultDto(r.fileId(), r.release(), r.fileName(), r.fps(), r.downloadCount(), r.fromTrusted(),
            r.hearingImpaired(), r.uploadDate());
    }
}
