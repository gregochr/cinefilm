package gregochr.cinefilm.subtitle;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves an edition's forced English subtitle as WebVTT on the edition's timeline. The {@code v} query parameter
 * (the subtitle's version, from the film detail) only busts the browser's cache: the server ignores it.
 */
@RestController
public class ForcedSubtitleController {

    private static final MediaType VTT = MediaType.parseMediaType("text/vtt;charset=UTF-8");

    private final ForcedSubtitleService service;

    /** Creates the controller over the forced subtitle service. */
    public ForcedSubtitleController(ForcedSubtitleService service) {
        this.service = service;
    }

    @GetMapping("/api/editions/{id}/forced-subtitles.vtt")
    ResponseEntity<String> forcedSubtitles(@PathVariable Long id) {
        return ResponseEntity.ok().contentType(VTT).cacheControl(CacheControl.noCache().cachePrivate())
            .body(service.vtt(id));
    }
}
