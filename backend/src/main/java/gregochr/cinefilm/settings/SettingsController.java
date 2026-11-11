package gregochr.cinefilm.settings;

import gregochr.cinefilm.settings.SettingsDtos.Settings;
import gregochr.cinefilm.settings.SettingsDtos.SettingsUpdate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the server settings. */
@RestController
public class SettingsController {

    private final ServerSettingsService settings;

    /** Creates the controller over the settings service. */
    public SettingsController(ServerSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/admin/settings")
    Settings get() {
        return settings.current();
    }

    @PutMapping("/api/admin/settings")
    Settings update(@RequestBody(required = false) SettingsUpdate body) {
        // A body of JSON null (or none) reaches the service as a missing value, which it answers with a 400.
        return settings.update(body == null ? null : body.minutes());
    }
}
