package gregochr.cinefilm.credits.learn;

import java.util.List;

/**
 * One item of the saved Plex archive: its length, its versions with their files, and its markers of every type.
 *
 * @param ratingKey Plex's key for the item, taken from the file name
 * @param durationMs the item's own duration, or null when Plex gave none
 * @param media the item's versions, in Plex's order
 * @param markers every marker Plex lists, whatever its type
 */
record ArchiveItem(long ratingKey, Long durationMs, List<Media> media, List<Marker> markers) {

    /** The marker type that holds credits. */
    static final String CREDITS = "credits";

    /**
     * One version of the item.
     *
     * @param durationMs the version's duration, or null
     * @param files the file paths of its parts, as Plex reports them
     */
    record Media(Long durationMs, List<String> files) {
    }

    /**
     * One marker.
     *
     * @param type Plex's marker type ({@code credits}, {@code intro}, ...)
     * @param startMs where it starts, or null when Plex gave no start
     * @param endMs where it ends, or null when Plex gave no end
     * @param finalMarker Plex's {@code final} flag (read, never used to decide anything)
     */
    record Marker(String type, Long startMs, Long endMs, boolean finalMarker) {
    }
}
