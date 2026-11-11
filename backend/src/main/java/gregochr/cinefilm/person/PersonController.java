package gregochr.cinefilm.person;

import gregochr.cinefilm.web.JsonEtag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Person page endpoint. */
@RestController
public class PersonController {

    private final PersonService service;
    private final JsonEtag etags;

    /** Creates the controller over the person service and the ETag helper. */
    public PersonController(PersonService service, JsonEtag etags) {
        this.service = service;
        this.etags = etags;
    }

    @GetMapping("/api/people/{id}")
    ResponseEntity<byte[]> get(HttpServletRequest request, @PathVariable Long id) {
        return etags.respond(request, service.get(id));
    }
}
