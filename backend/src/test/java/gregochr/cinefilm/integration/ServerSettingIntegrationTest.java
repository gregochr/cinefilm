package gregochr.cinefilm.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gregochr.cinefilm.settings.ServerSettingsService;
import gregochr.cinefilm.settings.SettingsDtos.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V12 on PostgreSQL 17: the single settings row exists with its default and its constraints hold. */
class ServerSettingIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ServerSettingsService settings;

    @Test
    void theMigrationInsertsTheOnlyRowWithTheDefaultOfFiveMinutes() {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM server_setting", Integer.class);
        Integer minutes = jdbc.queryForObject(
            "SELECT continue_watching_min_minutes FROM server_setting WHERE id = 1", Integer.class);

        assertThat(rows).isEqualTo(1);
        assertThat(minutes).isEqualTo(5);
        assertThat(settings.minWatchMs()).isEqualTo(5 * 60_000L);
    }

    @Test
    void theCheckConstraintRejectsAMinimumOutsideZeroToThirty() {
        assertThatThrownBy(() -> jdbc.update("UPDATE server_setting SET continue_watching_min_minutes = 31"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE server_setting SET continue_watching_min_minutes = -1"))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE server_setting SET continue_watching_min_minutes = 30");
        jdbc.update("UPDATE server_setting SET continue_watching_min_minutes = 0");
        jdbc.update("UPDATE server_setting SET continue_watching_min_minutes = 5");
    }

    @Test
    void thereCanBeOnlyOneRow() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO server_setting (id, continue_watching_min_minutes) VALUES (2, 5)"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aPutThroughTheServiceIsStoredAndTheCountComesBackFromTheRealSchema() {
        Settings updated = settings.update(12);

        assertThat(updated.continueWatchingMinMinutes()).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT continue_watching_min_minutes FROM server_setting",
            Integer.class)).isEqualTo(12);
        settings.update(5);
    }
}
