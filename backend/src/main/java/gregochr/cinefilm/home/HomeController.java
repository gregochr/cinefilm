package gregochr.cinefilm.home;

import gregochr.cinefilm.web.JsonEtag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Home endpoint. */
@RestController
public class HomeController {

    private final HomeService service;
    private final JsonEtag etags;

    /** Creates the controller over the Home service and the ETag helper. */
    public HomeController(HomeService service, JsonEtag etags) {
        this.service = service;
        this.etags = etags;
    }

    @GetMapping("/api/home")
    ResponseEntity<byte[]> home(HttpServletRequest request) {
        return etags.respond(request, service.home());
    }
}
