package gregochr.cinefilm.collection;

import gregochr.cinefilm.web.JsonEtag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Collection list and collection page endpoints. */
@RestController
public class CollectionController {

    private final CollectionService service;
    private final JsonEtag etags;

    /** Creates the controller over the collection service and the ETag helper. */
    public CollectionController(CollectionService service, JsonEtag etags) {
        this.service = service;
        this.etags = etags;
    }

    @GetMapping("/api/collections")
    ResponseEntity<byte[]> list(HttpServletRequest request) {
        return etags.respond(request, service.list());
    }

    @GetMapping("/api/collections/{id}")
    ResponseEntity<byte[]> get(HttpServletRequest request, @PathVariable Long id) {
        return etags.respond(request, service.get(id));
    }
}
