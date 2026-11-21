package gregochr.cinefilm.film;

/** Where an edition's credits marker came from; set and cleared together with the marker. */
public enum CreditsMarkerSource {
    /** Found by CineFilm's own credits detection. */
    AUTO,
    /** Imported from Plex's credits markers. */
    PLEX,
    /** Set by the owner. */
    OWNER
}
