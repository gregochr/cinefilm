package gregochr.cinefilm.plex;

import gregochr.cinefilm.plex.PlexDtos.PlexImportStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the Plex import panel. */
@RestController
public class PlexAdminController {

    private final PlexImportService imports;

    /** Creates the controller. */
    public PlexAdminController(PlexImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/api/admin/plex")
    PlexImportStatus status() {
        return imports.status();
    }

    @PostMapping("/api/admin/plex/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PlexImportStatus startImport() {
        imports.start();
        return imports.status();
    }
}
