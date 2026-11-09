package gregochr.cinefilm.plex;

import java.time.Instant;
import java.util.List;

/** The Plex import's API shapes. The report is also what is stored, as JSON, on the run. */
public final class PlexDtos {

    /** Longest list kept in a report; the totals beside it say how many there really were. */
    public static final int LIST_CAP = 200;

    private PlexDtos() {
    }

    /**
     * What an import did.
     *
     * @param plexFilms films Plex lists
     * @param paired Plex films with at least one file that CineFilm has
     * @param merged CineFilm films folded into another (same TMDB id)
     * @param editionsMoved editions moved to the film Plex says they belong to
     * @param metadataApplied films whose title, year, overview and dates were set from Plex
     * @param postersStored posters downloaded into the artwork store
     * @param backdropsStored backdrops downloaded into the artwork store
     * @param artworkFailures posters or backdrops that could not be downloaded or stored
     * @param invalidArtPaths Plex artwork paths refused because they are not plain library paths
     * @param tmdbUnavailable films whose TMDB details could not be fetched (retried next run)
     * @param watchedImported editions made watched
     * @param resumePointsImported editions given a resume position
     * @param keptNewer editions left alone because CineFilm's own state was newer
     * @param failures films that could not be imported (the others are complete)
     * @param failedFilms titles of those films, up to {@link #LIST_CAP}
     * @param notOnDisk titles of Plex films none of whose files CineFilm has, up to {@link #LIST_CAP}
     * @param notOnDiskTotal how many such films there are
     * @param onDiskNotInPlex CineFilm files that no Plex film points at
     * @param collections Plex collection names, up to {@link #LIST_CAP} (not imported)
     * @param collectionsTotal how many Plex collections there are
     */
    public record PlexImportReport(int plexFilms, int paired, int merged, int editionsMoved, int metadataApplied,
                                   int postersStored, int backdropsStored, int artworkFailures, int invalidArtPaths,
                                   int tmdbUnavailable, int watchedImported, int resumePointsImported, int keptNewer,
                                   int failures, List<String> failedFilms, List<String> notOnDisk, int notOnDiskTotal,
                                   int onDiskNotInPlex, List<String> collections, int collectionsTotal) {
    }

    /**
     * Live progress of a running import.
     *
     * @param processed Plex films handled so far
     * @param total Plex films to handle
     * @param current the title being handled, or null
     */
    public record PlexProgress(int processed, int total, String current) {
    }

    /**
     * The most recent finished import.
     *
     * @param startedAt when it started
     * @param finishedAt when it finished
     * @param error why it stopped without importing, or null
     * @param report what it did, or null when it stopped before it could say
     */
    public record LastImport(Instant startedAt, Instant finishedAt, String error, PlexImportReport report) {
    }

    /**
     * The Plex import panel.
     *
     * @param configured false when {@code PLEX_URL} or {@code PLEX_TOKEN} is blank (the panel is then hidden)
     * @param reachable whether Plex answers
     * @param running true while an import runs
     * @param progress live progress while running, else null
     * @param lastImport the last finished import, or null if none
     */
    public record PlexImportStatus(boolean configured, boolean reachable, boolean running, PlexProgress progress,
                                   LastImport lastImport) {
    }
}
