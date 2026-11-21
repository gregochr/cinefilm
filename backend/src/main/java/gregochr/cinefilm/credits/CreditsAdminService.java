package gregochr.cinefilm.credits;

import gregochr.cinefilm.credits.CreditsAdminDtos.Counts;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsList;
import gregochr.cinefilm.credits.CreditsAdminDtos.CreditsRow;
import gregochr.cinefilm.credits.CreditsAdminDtos.Plex;
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
import gregochr.cinefilm.plex.PlexCreditsMarker;
import gregochr.cinefilm.plex.PlexCreditsMarkerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Creates the service. */
    public CreditsAdminService(EditionRepository editions, PlexCreditsMarkerRepository markers) {
        this.editions = editions;
        this.markers = markers;
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
        Map<Long, Long> visible = editions.countVisibleByFilmIdIn(
                ordered.stream().map(e -> e.getFilm().getId()).distinct().toList()).stream()
            .collect(Collectors.toMap(FilmEditionCount::filmId, FilmEditionCount::count));
        List<Long> lastPartIds = new ArrayList<>();
        for (Edition edition : ordered) {
            List<MediaFile> originals = edition.originalParts();
            if (!originals.isEmpty()) {
                lastPartIds.add(originals.get(originals.size() - 1).getId());
            }
        }
        Map<Long, PlexCreditsMarker> rows = lastPartIds.isEmpty() ? Map.of()
            : markers.findAllById(lastPartIds).stream()
                .collect(Collectors.toMap(PlexCreditsMarker::getMediaFileId, m -> m));
        List<CreditsRow> items = new ArrayList<>();
        for (Edition edition : ordered) {
            int count = visible.getOrDefault(edition.getFilm().getId(), 0L).intValue();
            items.add(row(edition, count, rows));
        }
        return new CreditsList(counts, pageNumber, pageSize, total, items);
    }

    private static Counts counts(List<SourceCount> grouped) {
        long none = 0;
        long plex = 0;
        long owner = 0;
        long all = 0;
        for (SourceCount group : grouped) {
            all += group.count();
            if (group.source() == null) {
                none += group.count();
            } else if (group.source() == CreditsMarkerSource.PLEX) {
                plex += group.count();
            } else if (group.source() == CreditsMarkerSource.OWNER) {
                owner += group.count();
            }
        }
        return new Counts(none, plex, owner, all);
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
