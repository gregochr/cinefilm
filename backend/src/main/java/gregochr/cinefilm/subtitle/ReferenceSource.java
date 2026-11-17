package gregochr.cinefilm.subtitle;

/** What a forced subtitle file's timing fit is against ({@link ForcedSubtitleAlignment#getReferenceSource()}). */
public enum ReferenceSource {
    /** The film's own full English subtitle track. */
    EMBEDDED,
    /** A stored full English file from OpenSubtitles: shown, but never proof and never applied. */
    OPENSUBTITLES
}
