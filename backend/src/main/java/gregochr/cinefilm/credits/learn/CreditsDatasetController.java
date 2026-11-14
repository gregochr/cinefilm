package gregochr.cinefilm.credits.learn;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the credits dataset job, which reads the frames the credits classifier is trained on. */
@RestController
public class CreditsDatasetController {

    private final CreditsDatasetJob job;

    /** Creates the controller. */
    public CreditsDatasetController(CreditsDatasetJob job) {
        this.job = job;
    }

    @GetMapping("/api/admin/credits/dataset")
    DatasetStatus status() {
        return job.status();
    }

    @PostMapping("/api/admin/credits/dataset/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DatasetStatus start() {
        return job.start();
    }

    @PostMapping("/api/admin/credits/dataset/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    DatasetStatus stop() {
        return job.stop();
    }
}
