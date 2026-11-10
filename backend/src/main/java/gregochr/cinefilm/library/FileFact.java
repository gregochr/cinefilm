package gregochr.cinefilm.library;

import gregochr.cinefilm.film.GroupingSource;
import gregochr.cinefilm.film.MatchStatus;
import gregochr.cinefilm.film.Playability;
import gregochr.cinefilm.film.Presence;
import gregochr.cinefilm.library.FileNameParser.ParsedName;

/**
 * What the grouping passes know about one original file of a film the owner has not settled: where it lives in the
 * model and what its path says. A plain value, so the grouper and the repair rules are pure and testable.
 *
 * @param filmId the film
 * @param filmTitle the film's title
 * @param filmTmdbId the film's TMDB id, or null
 * @param filmStatus whether the film is matched
 * @param editionId the edition holding the file
 * @param editionName the edition's name
 * @param editionSource who grouped the edition
 * @param fileId the media file
 * @param path the path relative to the library root
 * @param partNumber the file's part number within its edition
 * @param durationMs the probed duration, or null
 * @param presence whether the last scan found it
 * @param playability whether it can be played
 * @param parsed what the path says
 * @param extraFiles whether the edition also holds a file that is not an original (a pre-made rendition)
 */
public record FileFact(long filmId, String filmTitle, Long filmTmdbId, MatchStatus filmStatus, long editionId,
                       String editionName, GroupingSource editionSource, long fileId, String path, int partNumber,
                       Long durationMs, Presence presence, Playability playability, ParsedName parsed,
                       boolean extraFiles) {

    /** True when the file can be a part of a joined edition: on disk, playable and with a duration. */
    public boolean joinable() {
        return presence == Presence.PRESENT && playability == Playability.PLAYABLE && durationMs != null
            && durationMs > 0;
    }

    /** The folder, comparison title and year: files with the same key are the same film whatever their edition tag. */
    public String filmKey() {
        String normal = path.replace('\\', '/');
        int slash = normal.lastIndexOf('/');
        String dir = slash < 0 ? "" : normal.substring(0, slash);
        return dir + "|" + parsed.comparisonTitle() + "|" + (parsed.year() == null ? "" : parsed.year());
    }

    /** The lower-cased edition tag, empty when the file has none. */
    public String tagKey() {
        return parsed.editionTag() == null ? "" : parsed.editionTag().toLowerCase(java.util.Locale.ROOT);
    }
}
