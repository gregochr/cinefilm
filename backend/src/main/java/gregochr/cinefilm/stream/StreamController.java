package gregochr.cinefilm.stream;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct-play streaming. Spring MVC turns a {@link Resource} body plus a {@code Range} request header into a
 * 206 partial response (HttpRange / ResourceRegion), and an unsatisfiable range into 416.
 */
@RestController
public class StreamController {

    private final StreamService service;

    public StreamController(StreamService service) {
        this.service = service;
    }

    @GetMapping("/api/editions/{id}/stream")
    ResponseEntity<Resource> stream(@PathVariable Long id) {
        Resource resource = new FileSystemResource(service.originalPath(id));
        MediaType type = MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM);
        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .body(resource);
    }
}
