package gregochr.cinefilm.library;

import gregochr.cinefilm.library.EditionDtos.EditionsView;
import gregochr.cinefilm.library.EditionDtos.SaveRequest;
import gregochr.cinefilm.library.EditionDtos.SaveResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Admin: Edit editions, arranging a film's files into editions and parts. */
@RestController
public class EditEditionsController {

    private final EditionArranger arranger;

    /** Creates the controller. */
    public EditEditionsController(EditionArranger arranger) {
        this.arranger = arranger;
    }

    @GetMapping("/api/admin/films/{id}/editions")
    EditionsView view(@PathVariable Long id) {
        return arranger.view(id);
    }

    @PutMapping("/api/admin/films/{id}/editions")
    SaveResult save(@PathVariable Long id, @RequestBody SaveRequest request) {
        return arranger.save(id, request);
    }
}
