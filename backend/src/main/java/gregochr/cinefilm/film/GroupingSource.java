package gregochr.cinefilm.film;

/** Who grouped an edition's files: the scan on its own, the Plex import, or the owner by hand. */
public enum GroupingSource {
    /** Grouped by the scan from the file names; the scan may regroup it. */
    AUTO,
    /** Grouped to match Plex; only the Plex import may split it. */
    PLEX,
    /** Settled by the owner; nothing else changes it. */
    OWNER
}
