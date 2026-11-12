package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.subtitle.ForcedNeedDtos.ChoiceRequest;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.FilmNeedDto;
import gregochr.cinefilm.subtitle.ForcedNeedDtos.NeedListDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin: which films need forced subtitles, and the owner's choice for each. */
@RestController
public class ForcedNeedController {

    private final ForcedNeedService service;

    /** Creates the controller over the forced-need service. */
    public ForcedNeedController(ForcedNeedService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/forced-subtitles/films")
    NeedListDto list() {
        return service.list();
    }

    @PutMapping("/api/admin/films/{id}/forced-need")
    FilmNeedDto choose(@PathVariable Long id, @RequestBody ChoiceRequest request) {
        return service.choose(id, request);
    }
}
