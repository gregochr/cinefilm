package gregochr.cinefilm.plex;

/**
 * Why the credits import stored nothing for a film or a file. The description is the plain label the Admin report
 * shows, with the count after it. Reasons about a whole Plex film count films; the others count CineFilm files.
 */
public enum PlexCreditsSkip {
    /** Plex did not answer for the item, or sent no usable answer. */
    PLEX_UNAVAILABLE("Films Plex did not answer for"),
    /** The item has no version with a file. */
    NO_MEDIA("Films Plex lists with no file"),
    /** A version has several files: a split edition, for which Plex has no markers. */
    MULTI_PART("Films split into parts"),
    /** Plex gave the item no duration. */
    ITEM_NO_DURATION("Films with no duration in Plex"),
    /** Plex gave a version no duration. */
    MEDIA_NO_DURATION("Films with a version that has no duration in Plex"),
    /** A version is more than 2 seconds longer or shorter than the item, so one marker cannot fit every copy. */
    DURATION_MISMATCH("Films whose versions differ in length"),
    /** Plex has no credits marker for the item. */
    NO_CREDITS_MARKER("Films with no credits marker in Plex"),
    /** Plex's marker is under 60% into the film or under a minute before its end. */
    MARKER_IMPLAUSIBLE("Films whose Plex marker is too early or too near the end"),
    /** The file has no size or modification time to tell a changed file by. */
    NO_FINGERPRINT("Files with no size or modification time"),
    /** CineFilm does not know the file's length. */
    LOCAL_DURATION_UNKNOWN("Files whose length CineFilm does not know"),
    /** The file's length is more than 2 seconds from the version Plex measured: the file changed since. */
    LOCAL_DURATION_DIFFERS("Files whose length differs from Plex's"),
    /** The marker, moved to this copy, is too early or too near the end of the file. */
    SHIFTED_IMPLAUSIBLE("Files whose moved marker is too early or too near the end"),
    /** The file changed or went while the import ran. */
    FILE_CHANGED("Files that changed during the import");

    private final String description;

    PlexCreditsSkip(String description) {
        this.description = description;
    }

    /** The plain label of what is counted. */
    public String description() {
        return description;
    }
}
