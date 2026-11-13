package gregochr.cinefilm.subtitle;

/** What a subtitle file holds: only the foreign-language lines (forced), or every line in English (full). */
public enum SubtitleKind {
    /** A forced-only English subtitle: {@code X.en.forced.srt}, or a download of one. */
    FORCED,
    /** A full English subtitle: {@code X.en.srt}, {@code X.en.sdh.srt}. */
    FULL
}
