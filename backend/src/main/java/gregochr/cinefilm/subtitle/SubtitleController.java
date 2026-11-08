package gregochr.cinefilm.subtitle;

import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves embedded subtitles as WebVTT for the player's {@code <track>} elements. */
@RestController
public class SubtitleController {

    private static final MediaType VTT = MediaType.parseMediaType("text/vtt;charset=UTF-8");

    private final SubtitleService service;

    /** Creates the controller over the subtitle service. */
    public SubtitleController(SubtitleService service) {
        this.service = service;
    }

    @GetMapping("/api/media-files/{id}/subtitles/{streamIndex}.vtt")
    ResponseEntity<Resource> subtitles(@PathVariable Long id, @PathVariable int streamIndex) {
        Path file = service.vtt(id, streamIndex);
        return ResponseEntity.ok().contentType(VTT).cacheControl(CacheControl.noCache().cachePrivate())
            .body(new FileSystemResource(file));
    }
}
