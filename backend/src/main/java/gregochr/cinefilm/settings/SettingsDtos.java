package gregochr.cinefilm.settings;

import tools.jackson.databind.JsonNode;

/** JSON shapes of the Admin settings endpoint. */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    /** The current settings and how many films are in progress under them (Library rule: removed films included). */
    public record Settings(int continueWatchingMinMinutes, int inProgressCount) {
    }

    /**
     * The body of {@code PUT /api/admin/settings}. The field is read as a raw JSON node so that Jackson's default
     * coercion (a fraction cut to an int, a string parsed as a number) cannot turn a bad value into a valid one.
     */
    public record SettingsUpdate(JsonNode continueWatchingMinMinutes) {

        /**
         * The minimum in whole minutes, or null when the field is missing, null, not a JSON integer (a fraction, a
         * string, a boolean, an object) or too large for an int; the service answers null with a 400.
         */
        public Integer minutes() {
            JsonNode node = continueWatchingMinMinutes;
            return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
        }
    }
}
