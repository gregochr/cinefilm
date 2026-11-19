package gregochr.cinefilm.christmas;

import gregochr.cinefilm.christmas.ChristmasDtos.ChoiceRequest;
import gregochr.cinefilm.christmas.ChristmasDtos.ChristmasView;
import gregochr.cinefilm.christmas.ChristmasDtos.FilmState;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the films Home's Christmas week shows, and the owner's say over them. */
@RestController
@RequestMapping("/api/admin/christmas")
public class ChristmasController {

    private final ChristmasFilms christmas;

    /** Creates the controller. */
    public ChristmasController(ChristmasFilms christmas) {
        this.christmas = christmas;
    }

    @GetMapping
    ChristmasView view() {
        return christmas.view();
    }

    @PutMapping("/{tmdbId}")
    ChristmasView choose(@PathVariable long tmdbId, @RequestBody ChoiceRequest request) {
        return christmas.choose(tmdbId, request.choice());
    }

    @GetMapping("/states")
    List<FilmState> states(@RequestParam(required = false, defaultValue = "") List<Long> filmIds) {
        return christmas.states(filmIds);
    }
}
