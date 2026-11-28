package gregochr.cinefilm.journey;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The player's route for the map of past journeys' link quality (never stored by the browser). */
@RestController
public class LinkMapController {

    private final LinkMapService linkMaps;

    /** Creates the controller over the link map service. */
    public LinkMapController(LinkMapService linkMaps) {
        this.linkMaps = linkMaps;
    }

    /**
     * The map: points and stretches per track and nothing that names a journey. Never cached, by the browser or a
     * proxy. {@code excludeJourney} leaves one journey out, so a replay of it is not matched against itself.
     */
    @GetMapping("/api/journeys/link-map")
    ResponseEntity<LinkMap> linkMap(@RequestParam(required = false) Long excludeJourney) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(linkMaps.linkMap(excludeJourney));
    }
}
