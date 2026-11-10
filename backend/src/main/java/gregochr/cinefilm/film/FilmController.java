package gregochr.cinefilm.film;

import gregochr.cinefilm.film.FilmDtos.FilmDetail;
import gregochr.cinefilm.film.FilmDtos.FilmList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Library listing and film detail endpoints. */
@RestController
public class FilmController {

    private final FilmService service;

    /** Creates the controller over the film service. */
    public FilmController(FilmService service) {
        this.service = service;
    }

    @GetMapping("/api/films")
    FilmList list(@RequestParam(required = false) String q,
                  @RequestParam(required = false) String sort,
                  @RequestParam(required = false) String genre,
                  @RequestParam(required = false) Integer decade,
                  @RequestParam(required = false) String show) {
        return service.list(new LibraryQuery(q, sort, genre, decade, show));
    }

    @GetMapping("/api/films/{id}")
    FilmDetail detail(@PathVariable Long id) {
        return service.detail(id);
    }
}
