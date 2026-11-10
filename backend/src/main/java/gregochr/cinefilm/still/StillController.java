package gregochr.cinefilm.still;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves the frame at a point of an edition as a JPEG, for the player's resume still. */
@RestController
public class StillController {

    private final StillService service;

    /** Creates the controller over the still service. */
    public StillController(StillService service) {
        this.service = service;
    }

    @GetMapping("/api/editions/{id}/still")
    ResponseEntity<byte[]> still(@PathVariable Long id, @RequestParam(required = false) Long atMs) {
        byte[] jpeg = service.still(id, atMs);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .body(jpeg);
    }
}
