package gregochr.cinefilm.film;

/** The owner's say on whether a film needs full English subtitles; no choice (null) leaves it to the automatic rule. */
public enum FullNeedChoice {
    /** The film needs full subtitles. */
    NEEDED,
    /** The film does not need them. */
    NOT_NEEDED,
    /** The film needs them, but the owner says none exist: it is done, and nothing searches for them. */
    NONE_EXIST
}
