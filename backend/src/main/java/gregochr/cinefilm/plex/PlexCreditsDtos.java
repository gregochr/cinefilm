package gregochr.cinefilm.plex;

import java.time.Instant;
import java.util.List;

/** The credits import's API shapes. */
public final class PlexCreditsDtos {

    private PlexCreditsDtos() {
    }

    /**
     * How many films or files were skipped for one reason.
     *
     * @param reason the reason's code
     * @param description the plain label of what was counted ("Films split into parts"); the count follows it
     * @param count how many
     */
    public record SkipCount(String reason, String description, int count) {
    }

    /**
     * What a credits import did.
     *
     * @param plexFilms films Plex lists
     * @param matchedFilms Plex films with at least one file CineFilm has (the ones asked about)
     * @param imported markers stored (one per CineFilm file)
     * @param unmatched files whose path matches none of the versions Plex then listed for their film
     * @param skipped what was not stored, by reason, only the reasons that happened
     */
    public record PlexCreditsReport(int plexFilms, int matchedFilms, int imported, int unmatched,
                                    List<SkipCount> skipped) {
    }

    /**
     * Live progress of a running import.
     *
     * @param processed films asked about so far
     * @param total films to ask about
     */
    public record PlexCreditsProgress(int processed, int total) {
    }

    /**
     * The most recent finished import (kept until the server restarts).
     *
     * @param startedAt when it started
     * @param finishedAt when it finished
     * @param error why it stopped without finishing, or null
     * @param report what it did, or null when it stopped before it could say
     */
    public record LastCreditsImport(Instant startedAt, Instant finishedAt, String error,
                                    PlexCreditsReport report) {
    }

    /**
     * The credits import panel.
     *
     * @param running true while an import runs
     * @param progress live progress while running, else null
     * @param lastImport the last finished import, or null if none since the server started
     */
    public record PlexCreditsStatus(boolean running, PlexCreditsProgress progress, LastCreditsImport lastImport) {
    }
}
