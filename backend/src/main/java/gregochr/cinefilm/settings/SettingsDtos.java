package gregochr.cinefilm.settings;

/** JSON shapes of the Admin settings endpoint. */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    /** The current settings and how many films are in progress under them (Library rule: removed films included). */
    public record Settings(int continueWatchingMinMinutes, int inProgressCount) {
    }

    /** The body of {@code PUT /api/admin/settings}. */
    public record SettingsUpdate(Integer continueWatchingMinMinutes) {
    }
}
