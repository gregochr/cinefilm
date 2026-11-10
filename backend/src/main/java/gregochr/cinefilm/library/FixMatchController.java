package gregochr.cinefilm.library;

import gregochr.cinefilm.library.AdminDtos.MatchApplied;
import gregochr.cinefilm.library.AdminDtos.MatchRequest;
import gregochr.cinefilm.library.AdminDtos.MatchResultDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin: Fix match, the owner's choice of TMDB film for a film that needs one. */
@RestController
public class FixMatchController {

    private final FixMatch fixMatch;

    /** Creates the controller. */
    public FixMatchController(FixMatch fixMatch) {
        this.fixMatch = fixMatch;
    }

    @GetMapping("/api/admin/films/{id}/match")
    List<MatchResultDto> search(@PathVariable Long id, @RequestParam(required = false) String query,
                                @RequestParam(required = false) Integer year) {
        return fixMatch.search(id, query, year);
    }

    @PostMapping("/api/admin/films/{id}/match")
    MatchApplied apply(@PathVariable Long id, @RequestBody MatchRequest request) {
        return fixMatch.apply(id, request.tmdbId());
    }
}
