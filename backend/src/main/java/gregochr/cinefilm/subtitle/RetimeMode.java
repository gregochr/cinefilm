package gregochr.cinefilm.subtitle;

import java.util.Locale;

/**
 * The setting {@code cinefilm.subtitles.retime} (docs/engineering/subtitle-frame-rate-plan.md): what
 * {@link SubtitleRetimeJob} may do.
 */
public enum RetimeMode {
    /** The job writes nothing. Also what an unknown value means. */
    OFF,
    /** The job puts downloads on the film's timeline. */
    ON,
    /** The rollback: the job puts every retimed download back and decides nothing. */
    RESTORE;

    /**
     * Reads the setting.
     *
     * @param setting {@code off}, {@code on} or {@code restore} in any case; anything else, including null, is off
     * @return the mode
     */
    public static RetimeMode parse(String setting) {
        if (setting == null) {
            return OFF;
        }
        return switch (setting.trim().toLowerCase(Locale.ROOT)) {
            case "on" -> ON;
            case "restore" -> RESTORE;
            default -> OFF;
        };
    }
}
