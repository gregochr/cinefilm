package gregochr.cinefilm.plex;

import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmRepository;
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
    private final FilmRepository films;

    /** Creates the controller over the artwork store and the films that reference its files. */
    public ArtworkController(ArtworkStore store, FilmRepository films) {
        this.store = store;
        this.films = films;
    }

    @GetMapping("/api/art/{name}")
    ResponseEntity<Resource> art(@PathVariable String name) {
        String path = ArtworkStore.URL_PREFIX + name;
        // Only files some film refers to are served (SECURITY.md rule 3): a stray file in the folder is a 404.
        var file = store.file(name).filter(f -> films.existsByPosterPathOrBackdropPath(path, path))
            .orElseThrow(() -> new NotFoundException("No such artwork"));
        return ResponseEntity.ok().contentType(ArtworkStore.mediaType(name))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
            .body(new FileSystemResource(file));
    }
}
