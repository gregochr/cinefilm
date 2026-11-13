package gregochr.cinefilm.plex;

import gregochr.cinefilm.plex.PlexCreditsDtos.PlexCreditsStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the one-off import of Plex's credits markers. */
@RestController
public class PlexCreditsController {

    private final PlexCreditsImportService imports;

    /** Creates the controller. */
    public PlexCreditsController(PlexCreditsImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/api/admin/plex/credits")
    PlexCreditsStatus status() {
        return imports.status();
    }

    @PostMapping("/api/admin/plex/credits/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PlexCreditsStatus startImport() {
        imports.start();
        return imports.status();
    }
}
