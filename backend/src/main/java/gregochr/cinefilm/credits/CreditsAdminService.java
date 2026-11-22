package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsAdminDtos.Counts;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsList;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsRow;
import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.credits.CreditsAdminDtos.Plex;
import gregochr.cinefilm.credits.CreditsApply.Applied;
import gregochr.cinefilm.credits.CreditsApply.AutoInputs;
import gregochr.cinefilm.credits.CreditsDenyFile.DenyList;
import gregochr.cinefilm.credits.CreditsDenyFile.Parsed;
import gregochr.cinefilm.credits.CreditsApply.Part;
import gregochr.cinefilm.credits.CreditsApply.PlexRow;
import gregochr.cinefilm.credits.CreditsApply.PlexStatus;
import gregochr.cinefilm.film.CreditsMarkerSource;
import gregochr.cinefilm.film.Edition;
import gregochr.cinefilm.film.EditionRepository;
import gregochr.cinefilm.film.EditionRepository.FilmEditionCount;
import gregochr.cinefilm.film.EditionRepository.SourceCount;
import gregochr.cinefilm.film.EditionTimeline;
import gregochr.cinefilm.film.Film;
import gregochr.cinefilm.film.MediaFile;
import gregochr.cinefilm.library.LibraryLock;
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Admin End credits list: every visible edition with its marker, where it came from, and what Plex says. A page
 * costs a fixed number of queries however many editions it holds (the filter counts, the page of ids, the editions
 * with their files and film, their films' visible-edition counts, their Plex rows).
 */
@Service
public class CreditsAdminService {

    /** The most rows a page may hold. */
    public static final int MAX_SIZE = 100;

    private final EditionRepository editions;
    private final PlexCreditsMarkerRepository markers;
    private final LibraryLock lock;
    private final CreditsDenyListKeeper denyList;
    private final CreditsAutoReader auto;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    /** Creates the service. */
    public CreditsAdminService(EditionRepository editions, PlexCreditsMarkerRepository markers, LibraryLock lock,
                               CreditsDenyListKeeper denyList, CreditsAutoReader auto,
                               PlatformTransactionManager transactions) {
        this.editions = editions;
        this.markers = markers;
        this.lock = lock;
        this.denyList = denyList;
        this.auto = auto;
        this.readTx = new TransactionTemplate(transactions);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactions);
    }

    /**
     * Sets the owner's time for an edition ({@code OWNER}, over a {@code PLEX} marker too).
     *
     * @param editionId the edition
     * @param markerMs the time in milliseconds; null (a missing, non-whole or negative value) is a 400
     * @return the edition's row as it now stands
     * @throws ConflictException when the library lock is held; nothing is written
     * @throws NotFoundException when the edition is unknown or hidden from browse
     * @throws BadRequestException for a null time, an edition with no timeline, or a time outside the guard
     */
    public CreditsRow set(long editionId, Long markerMs) {
        write(editionId, edition -> {
            if (markerMs == null) {
                throw new BadRequestException("Invalid request");
            }
            long total;
            try {
                total = EditionTimeline.of(edition).totalMs();
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("This film's length isn't known");
            }
            if (!EditionGuard.plausible(markerMs, total)) {
                throw new BadRequestException("That time is outside where end credits can start");
            }
            edition.setCreditsMarkerMs(markerMs);
            edition.setCreditsMarkerSource(CreditsMarkerSource.OWNER);
            editions.save(edition);
        });
        return view(editionId);
    }

    /**
     * Clears the owner's time: an {@code OWNER} edition goes back to what the automatic rules say (Plex's marker when
     * usable, otherwise an {@code AUTO} marker when step 3 holds with the deny list the latest pass kept in force,
     * otherwise none), whatever {@code cinefilm.credits.apply.enabled} says. Any other edition is left alone.
     *
     * @param editionId the edition
     * @return the edition's row as it now stands
     * @throws ConflictException when the library lock is held; nothing is written
     * @throws NotFoundException when the edition is unknown or hidden from browse
     */
    public CreditsRow clear(long editionId) {
        write(editionId, edition -> {
            if (edition.getCreditsMarkerSource() != CreditsMarkerSource.OWNER) {
                return;
            }
            // The list in force of the latest pass, held in memory: Clear never reads the file. One snapshot, whole.
            Parsed inForce = denyList.current();
            DenyList list = inForce == null ? null : inForce.list();
            AutoInputs autoInputs = auto.lookup(List.of(edition), list, auto.listedUnitKeys(list)).inputs(edition);
            Applied applied = CreditsApplier.applyEdition(edition, plexRows(List.of(edition)), null, autoInputs);
            edition.setCreditsMarkerMs(applied.markerMs());
            edition.setCreditsMarkerSource(applied.source());
            editions.save(edition);
        });
        return view(editionId);
    }

    /**
     * The one write path: tries the credits lock once (never waiting), then reads, changes and commits in one
     * transaction, and only then gives the lock up, so a pass waiting for the lock never reads the old marker. The
     * answer is built by the caller after this returns.
     */
    private void write(long editionId, Consumer<Edition> change) {
        if (!lock.tryAcquire(LibraryLock.CREDITS)) {
            throw new ConflictException(lock.busyText());
        }
        try {
            writeTx.executeWithoutResult(status -> change.accept(visible(editionId)));
        } finally {
            lock.release();
        }
    }

    private Edition visible(long editionId) {
        return editions.findWithFilesAndFilmByIdIn(List.of(editionId)).stream()
            .filter(e -> !e.hiddenFromBrowse()).findFirst()
            .orElseThrow(() -> new NotFoundException("No such edition"));
    }

    private CreditsRow view(long editionId) {
        return readTx.execute(status -> rows(List.of(visible(editionId)))).get(0);
    }

    /**
     * One page of the list.
     *
     * @param filter which editions to show
     * @param page the 0-based page (below 0 counts as 0)
     * @param size the rows per page (at least 1, capped at {@link #MAX_SIZE})
     * @return the page with the four filters' counts
     */
    @Transactional(readOnly = true)
    public CreditsList list(CreditsFilter filter, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), MAX_SIZE);
        Counts counts = counts(editions.countVisibleBySource());
        long total = switch (filter) {
            case NONE -> counts.none();
            case PLEX -> counts.plex();
            case AUTO -> counts.auto();
            case OWNER -> counts.owner();
            case ALL -> counts.all();
        };
        List<Long> ids = editions.findCreditsPageIds(filter.anySource(), filter.unsettled(), filter.source(),
            PageRequest.of(pageNumber, pageSize));
        if (ids.isEmpty()) {
            return new CreditsList(counts, pageNumber, pageSize, total, List.of());
        }
        Map<Long, Edition> loaded = editions.findWithFilesAndFilmByIdIn(ids).stream()
            .collect(Collectors.toMap(Edition::getId, e -> e));
        List<Edition> ordered = ids.stream().map(loaded::get).filter(Objects::nonNull).toList();
        List<CreditsRow> items = rows(ordered);
        return new CreditsList(counts, pageNumber, pageSize, total, items);
    }

    /** The rows of these editions, in order: one grouped read of the film counts and one of Plex's rows. */
    private List<CreditsRow> rows(List<Edition> ordered) {
        Map<Long, Long> visible = editions.countVisibleByFilmIdIn(
                ordered.stream().map(e -> e.getFilm().getId()).distinct().toList()).stream()
            .collect(Collectors.toMap(FilmEditionCount::filmId, FilmEditionCount::count));
        Map<Long, PlexCreditsMarker> rows = plexRows(ordered);
        List<CreditsRow> items = new ArrayList<>();
        for (Edition edition : ordered) {
            int count = visible.getOrDefault(edition.getFilm().getId(), 0L).intValue();
            items.add(row(edition, count, rows));
        }
        return items;
    }

    private Map<Long, PlexCreditsMarker> plexRows(List<Edition> ordered) {
        List<Long> lastPartIds = new ArrayList<>();
        for (Edition edition : ordered) {
            List<MediaFile> originals = edition.originalParts();
            if (!originals.isEmpty()) {
                lastPartIds.add(originals.get(originals.size() - 1).getId());
            }
        }
        return lastPartIds.isEmpty() ? Map.of()
            : markers.findAllById(lastPartIds).stream()
                .collect(Collectors.toMap(PlexCreditsMarker::getMediaFileId, m -> m));
    }

    private static Counts counts(List<SourceCount> grouped) {
        long none = 0;
        long plex = 0;
        long auto = 0;
        long owner = 0;
        long all = 0;
        for (SourceCount group : grouped) {
            all += group.count();
            if (group.source() == null) {
                none += group.count();
            } else if (group.source() == CreditsMarkerSource.PLEX) {
                plex += group.count();
            } else if (group.source() == CreditsMarkerSource.AUTO) {
                auto += group.count();
            } else if (group.source() == CreditsMarkerSource.OWNER) {
                owner += group.count();
            }
        }
        return new Counts(none, plex, auto, owner, all);
    }

    private static CreditsRow row(Edition edition, int editionCount, Map<Long, PlexCreditsMarker> rows) {
        Film film = edition.getFilm();
        List<MediaFile> originals = edition.originalParts();
        List<Part> parts = originals.stream().map(f -> new Part(f.getId(), f.timelineDurationMs())).toList();
        MediaFile last = originals.isEmpty() ? null : originals.get(originals.size() - 1);
        PlexCreditsMarker row = last == null ? null : rows.get(last.getId());
        PlexStatus status = CreditsApply.plexStatus(parts, last == null ? null : last.fingerprint(),
            row == null ? null : new PlexRow(row.getFingerprint(), row.getStartMs()));
        return new CreditsRow(edition.getId(), film.getId(), film.getTitle(), film.getYear(), edition.getName(),
            editionCount, runtimeMs(parts), edition.getCreditsMarkerMs(), edition.getCreditsMarkerSource(),
            new Plex(status.state(), status.markerMs()));
    }

    private static Long runtimeMs(List<Part> parts) {
        try {
            return EditionTimeline.of(parts.stream().map(Part::timelineDurationMs).toList()).totalMs();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
