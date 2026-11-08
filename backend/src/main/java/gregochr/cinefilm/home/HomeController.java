package gregochr.cinefilm.home;

import gregochr.cinefilm.home.HomeDtos.Home;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Home endpoint. */
@RestController
public class HomeController {

    private final HomeService service;

    public HomeController(HomeService service) {
        this.service = service;
    }

    @GetMapping("/api/home")
    Home home() {
        return service.home();
    }
}
