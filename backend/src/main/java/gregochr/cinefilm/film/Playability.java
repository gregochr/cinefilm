package gregochr.cinefilm.film;

/** Whether a file can be played by direct play. Only {@link #PLAYABLE} files appear in browse. */
public enum Playability {
    PLAYABLE,
    /** FairPlay-protected (DRM), cannot be played. */
    PROTECTED,
    /** An {@code .avi} or {@code .mkv} that Safari cannot direct-play. */
    NEEDS_CONVERSION,
    /** ffprobe could not read the file; retried on the next scan. */
    UNREADABLE
}
