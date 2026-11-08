package gregochr.cinefilm.person;

import gregochr.cinefilm.person.PersonDtos.PersonPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Person page endpoint. */
@RestController
public class PersonController {

    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping("/api/people/{id}")
    PersonPage get(@PathVariable Long id) {
        return service.get(id);
    }
}
