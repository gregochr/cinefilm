package gregochr.cinefilm.plex;

import gregochr.cinefilm.config.BadRequestException;
import gregochr.cinefilm.config.NotFoundException;
import gregochr.cinefilm.film.FilmRepository;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves stored artwork, whole or resized to one of four widths. Behind the session like every {@code /api} route; the
 * name is validated, never a path, and the width is one of four constants (SECURITY.md rules 3 and 10).
 */
@RestController
public class ArtworkController {

    private static final String IMMUTABLE = "private, max-age=31536000, immutable";

    private final ArtworkStore store;
    private final FilmRepository films;
    private final ResizedArtwork resized;

    /** Creates the controller over the artwork store, the films that reference its files and the resized copies. */
    public ArtworkController(ArtworkStore store, FilmRepository films, ResizedArtwork resized) {
        this.store = store;
        this.films = films;
        this.resized = resized;
    }

    @GetMapping("/api/art/{name}")
    ResponseEntity<?> art(@PathVariable String name, @RequestParam(name = "w", required = false) String w) {
        String path = ArtworkStore.URL_PREFIX + name;
        // Only files some film refers to are served (SECURITY.md rule 3): a stray file in the folder is a 404.
        var file = store.file(name).filter(f -> films.existsByPosterPathOrBackdropPath(path, path))
            .orElseThrow(() -> new NotFoundException("No such artwork"));
        if (w == null) {
            return stored(name, file, IMMUTABLE);
        }
        int width = ResizedArtwork.parseWidth(w)
            .orElseThrow(() -> new BadRequestException("Width must be 342, 500, 780 or 1280"));
        ResizedArtwork.Result result = resized.resized(name, file, width);
        return switch (result.outcome()) {
            case RESIZED -> ResponseEntity.ok().contentType(ArtworkStore.mediaType(name))
                .header(HttpHeaders.CACHE_CONTROL, IMMUTABLE).body(result.bytes());
            case NOT_WIDER, NOT_SMALLER -> stored(name, file, IMMUTABLE);
            case UNREADABLE -> stored(name, file, CacheControl.maxAge(Duration.ofDays(1)).cachePrivate()
                .getHeaderValue());
            case BUSY -> stored(name, file, CacheControl.noStore().getHeaderValue());
        };
    }

    private static ResponseEntity<FileSystemResource> stored(String name, Path file, String cacheControl) {
        return ResponseEntity.ok().contentType(ArtworkStore.mediaType(name))
            .header(HttpHeaders.CACHE_CONTROL, cacheControl).body(new FileSystemResource(file));
    }
}
