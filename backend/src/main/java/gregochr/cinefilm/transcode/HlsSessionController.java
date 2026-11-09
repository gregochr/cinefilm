package gregochr.cinefilm.transcode;

import gregochr.cinefilm.transcode.SessionDtos.CreateRequest;
import gregochr.cinefilm.transcode.SessionDtos.SessionCreated;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Adaptive-streaming sessions: create, the playlists and segments, and end. Every route needs the session cookie. */
@RestController
public class HlsSessionController {

    private static final MediaType PLAYLIST = MediaType.parseMediaType("application/vnd.apple.mpegurl");
    private static final MediaType SEGMENT = MediaType.parseMediaType("video/mp2t");

    private final SessionService service;

    /** Creates the controller over the session service. */
    public HlsSessionController(SessionService service) {
        this.service = service;
    }

    @PostMapping("/api/editions/{id}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionCreated create(@PathVariable Long id, @RequestBody CreateRequest request) {
        return service.create(id, request);
    }

    @GetMapping("/api/sessions/{id}/master.m3u8")
    ResponseEntity<String> master(@PathVariable String id) {
        return playlist(service.master(id));
    }

    @GetMapping("/api/sessions/{id}/{rung}/index.m3u8")
    ResponseEntity<String> media(@PathVariable String id, @PathVariable String rung) {
        return playlist(service.media(id, rung));
    }

    @GetMapping("/api/sessions/{id}/{rung}/{file}")
    ResponseEntity<Resource> segment(@PathVariable String id, @PathVariable String rung,
                                     @PathVariable String file) {
        return ResponseEntity.ok().contentType(SEGMENT).cacheControl(CacheControl.noStore())
            .body(new FileSystemResource(service.segment(id, rung, file)));
    }

    @DeleteMapping("/api/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(@PathVariable String id) {
        service.end(id);
    }

    private static ResponseEntity<String> playlist(String text) {
        return ResponseEntity.ok().contentType(PLAYLIST).cacheControl(CacheControl.noStore()).body(text);
    }

    @ExceptionHandler(SegmentNotReadyException.class)
    ResponseEntity<Map<String, String>> notReady(SegmentNotReadyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
            .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SessionUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(SessionUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}
