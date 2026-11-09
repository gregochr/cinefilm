package gregochr.cinefilm.plex;

import gregochr.cinefilm.config.NotFoundException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves stored artwork. Behind the session like every {@code /api} route; the name is validated, never a path. */
@RestController
public class ArtworkController {

    private final ArtworkStore store;

    /** Creates the controller over the artwork store. */
    public ArtworkController(ArtworkStore store) {
        this.store = store;
    }

    @GetMapping("/api/art/{name}")
    ResponseEntity<Resource> art(@PathVariable String name) {
        var file = store.file(name).orElseThrow(() -> new NotFoundException("No such artwork"));
        return ResponseEntity.ok().contentType(ArtworkStore.mediaType(name))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
            .body(new FileSystemResource(file));
    }
}
