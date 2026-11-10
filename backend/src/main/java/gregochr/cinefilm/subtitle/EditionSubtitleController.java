package gregochr.cinefilm.subtitle;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves an edition's embedded subtitles as WebVTT on the edition's own timeline, for the player's {@code <track>}
 * elements. The stream index is part 1's, so a film split across several files has the same tracks as one in a single
 * piece and no part number appears anywhere.
 */
@RestController
public class EditionSubtitleController {

    private static final MediaType VTT = MediaType.parseMediaType("text/vtt;charset=UTF-8");

    private final EditionSubtitleService service;

    /** Creates the controller over the edition subtitle service. */
    public EditionSubtitleController(EditionSubtitleService service) {
        this.service = service;
    }

    @GetMapping("/api/editions/{id}/subtitles/{streamIndex}.vtt")
    ResponseEntity<String> subtitles(@PathVariable Long id, @PathVariable int streamIndex) {
        return ResponseEntity.ok().contentType(VTT).cacheControl(CacheControl.noCache().cachePrivate())
            .body(service.vtt(id, streamIndex));
    }
}
