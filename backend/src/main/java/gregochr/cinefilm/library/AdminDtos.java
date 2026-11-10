package gregochr.cinefilm.library;

import java.time.Instant;
import java.util.List;

/** The Admin API shapes for the library scan and the attention list. */
public final class AdminDtos {

    private AdminDtos() {
    }

    /**
     * Live progress of a running scan.
     *
     * @param processed files handled so far
     * @param total files found on disk (0 while the folder is still being walked)
     * @param current the file being handled, or null
     */
    public record ProgressDto(int processed, int total, String current) {
    }

    /**
     * The most recent finished scan.
     *
     * @param startedAt when it started
     * @param finishedAt when it finished
     * @param trigger startup, nightly or admin
     * @param filesSeen film files found on disk
     * @param newFiles files seen for the first time
     * @param missingFiles files that were there before and are gone
     * @param error a short problem note, or null
     */
    public record LastScanDto(Instant startedAt, Instant finishedAt, String trigger, int filesSeen, int newFiles,
                              int missingFiles, String error) {
    }

    /**
     * What the library holds, as counts.
     *
     * @param films films shown in browse
     * @param newFiles files added by the last scan
     * @param missing files that have vanished from disk
     * @param protectedFiles FairPlay-protected files
     * @param needsConversion {@code .avi} and {@code .mkv} files
     * @param needsMatch films with no confident TMDB match
     * @param unreadable files ffprobe could not read
     */
    public record CountsDto(long films, long newFiles, long missing, long protectedFiles, long needsConversion,
                            long needsMatch, long unreadable) {
    }

    /**
     * The Library scan panel.
     *
     * @param running true while a scan runs
     * @param folder the library folder
     * @param progress live progress while running, else null
     * @param lastScan the last finished scan, or null if none
     * @param counts the counts
     * @param ratingsLastRefreshedAt when ratings were last fetched for any film, or null if never
     * @param ratingsRunning true while a ratings refresh runs
     * @param ratingsEnabled true when an OMDb API key is configured
     */
    public record ScanStatusDto(boolean running, String folder, ProgressDto progress, LastScanDto lastScan,
                                CountsDto counts, Instant ratingsLastRefreshedAt, boolean ratingsRunning,
                                boolean ratingsEnabled) {
    }

    /**
     * A TMDB candidate shown read-only against a film that needs a match.
     *
     * @param tmdbId TMDB id
     * @param title title
     * @param year release year, or null
     * @param runtimeMinutes runtime in minutes, or null
     * @param posterPath poster path, or null
     */
    public record CandidateDto(long tmdbId, String title, Integer year, Integer runtimeMinutes, String posterPath) {
    }

    /**
     * One entry of Films needing attention.
     *
     * @param kind needs-match, protected, needs-conversion, unreadable, multiple-files or parts-mismatch
     * @param filmId the film
     * @param title its title
     * @param year its year, or null
     * @param detail a one-line explanation
     * @param files the original file names involved
     * @param candidates TMDB candidates (needs-match only, otherwise empty)
     */
    public record AttentionItem(String kind, Long filmId, String title, Integer year, String detail,
                                List<String> files, List<CandidateDto> candidates) {
    }

    /**
     * Films needing attention.
     *
     * @param items the entries, grouped by kind
     */
    public record Attention(List<AttentionItem> items) {
    }

    /**
     * The grouping report.
     *
     * @param items the newest lines, newest first
     */
    public record GroupingReport(List<GroupingChangeDto> items) {
    }

    /**
     * One TMDB result in the Fix match panel.
     *
     * @param tmdbId TMDB id
     * @param title title
     * @param year release year, or null
     * @param runtimeMinutes runtime in minutes, or null
     * @param posterPath poster path, or null
     * @param director the directors, comma separated, or null
     * @param cast the first few cast members
     */
    public record MatchResultDto(long tmdbId, String title, Integer year, Integer runtimeMinutes, String posterPath,
                                 String director, List<String> cast) {
    }

    /**
     * The body of a Fix match apply.
     *
     * @param tmdbId the TMDB film the owner picked
     */
    public record MatchRequest(Long tmdbId) {
    }

    /**
     * What applying a match did.
     *
     * @param filmId the film that now holds the files (the existing film when this one was joined into it)
     * @param joined true when another film already had that TMDB id and this one was joined into it
     */
    public record MatchApplied(Long filmId, boolean joined) {
    }

    /**
     * One line of the grouping report.
     *
     * @param happenedAt when it happened
     * @param kind split, join or flag
     * @param summary what happened, in plain words
     */
    public record GroupingChangeDto(Instant happenedAt, String kind, String summary) {
    }
}
