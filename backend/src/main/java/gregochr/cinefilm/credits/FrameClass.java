package gregochr.cinefilm.credits;

/** What one sampled frame looks like for the purpose of finding the credits. */
public enum FrameClass {
    /** Almost all dark: a fade, a black gap or an empty night sky. */
    BLACK,
    /** Mostly dark with a little bright text in lines near the middle. */
    TEXT_ON_DARK,
    /** Anything else: the film itself. */
    PICTURE
}
