package gregochr.cinefilm.film;

import gregochr.cinefilm.web.JsonEtag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Library listing and film detail endpoints. */
@RestController
public class FilmController {

    private final FilmService service;
    private final JsonEtag etags;

    /** Creates the controller over the film service and the ETag helper. */
    public FilmController(FilmService service, JsonEtag etags) {
        this.service = service;
        this.etags = etags;
    }

    @GetMapping("/api/films")
    ResponseEntity<byte[]> list(HttpServletRequest request,
                  @RequestParam(required = false) String q,
                  @RequestParam(required = false) String sort,
                  @RequestParam(required = false) String genre,
                  @RequestParam(required = false) Integer decade,
                  @RequestParam(required = false) String show) {
        return etags.respond(request, service.list(new LibraryQuery(q, sort, genre, decade, show)));
    }

    @GetMapping("/api/films/{id}")
    ResponseEntity<byte[]> detail(HttpServletRequest request, @PathVariable Long id) {
        return etags.respond(request, service.detail(id));
    }
}
