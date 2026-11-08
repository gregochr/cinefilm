package gregochr.cinefilm.collection;

import gregochr.cinefilm.collection.CollectionDtos.CollectionPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Collection page endpoint. */
@RestController
public class CollectionController {

    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @GetMapping("/api/collections/{id}")
    CollectionPage get(@PathVariable Long id) {
        return service.get(id);
    }
}
