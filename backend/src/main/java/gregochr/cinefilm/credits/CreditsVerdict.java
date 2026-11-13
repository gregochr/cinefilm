package gregochr.cinefilm.credits;

/** How sure the analysis is about where the credits start. */
public enum CreditsVerdict {
    /** Every rule met and no subtitle contradiction: safe to apply. */
    FOUND,
    /** A rule narrowly broken or a subtitle contradiction: stored with reasons, not applied. */
    UNSURE,
    /** No run of black or text on a dark screen that qualifies as credits. */
    NOT_FOUND
}
