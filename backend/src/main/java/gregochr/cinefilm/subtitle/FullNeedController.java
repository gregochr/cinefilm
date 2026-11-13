package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedFilmDto;
import gregochr.cinefilm.subtitle.FullNeedDtos.FullNeedListDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin: which films need full English subtitles, and the owner's choice for each. */
@RestController
public class FullNeedController {

    private final FullNeedService service;

    /** Creates the controller over the full-need service. */
    public FullNeedController(FullNeedService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/subtitles/full-need")
    FullNeedListDto list() {
        return service.list();
    }

    @PutMapping("/api/admin/films/{id}/full-need")
    FullNeedFilmDto choose(@PathVariable Long id, @RequestBody ChoiceRequest request) {
        return service.choose(id, request);
    }
}
