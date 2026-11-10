package gregochr.cinefilm.collection;

import gregochr.cinefilm.collection.CollectionDtos.CollectionList;
import gregochr.cinefilm.collection.CollectionDtos.CollectionPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Collection list and collection page endpoints. */
@RestController
public class CollectionController {

    private final CollectionService service;

    /** Creates the controller over the collection service. */
    public CollectionController(CollectionService service) {
        this.service = service;
    }

    @GetMapping("/api/collections")
    CollectionList list() {
        return service.list();
    }

    @GetMapping("/api/collections/{id}")
    CollectionPage get(@PathVariable Long id) {
        return service.get(id);
    }
}
