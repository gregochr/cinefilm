package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.health.BoundedText;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Admin routes that give a journey a train's file and take its train away. */
@RestController
public class JourneyTrainController {

    private final JourneyTrainService trains;

    /** Creates the controller over the train service. */
    public JourneyTrainController(JourneyTrainService trains) {
        this.trains = trains;
    }

    /**
     * Gives the journey the train in the body. The body is read here as UTF-8 text with a hard stop at
     * {@link JourneyTrainService#MAX_BODY_LENGTH} characters, so an oversized body is never held in memory: it
     * reaches the service as null, which refuses it. Either route answers 409 {@code Try again} when two requests
     * clash: on the run's unique key, on the journey's foreign key (a run swept while another journey is being given
     * it) or, as a {@link PessimisticLockingFailureException}, when the database ends one of two requests that wait
     * on each other. A retry gets the true answer.
     */
    @PutMapping("/api/admin/journeys/{id}/train")
    JourneyTrainService.Stored give(@PathVariable long id, HttpServletRequest request) throws IOException {
        String body = BoundedText.read(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8),
            JourneyTrainService.MAX_BODY_LENGTH);
        try {
            return trains.give(id, body);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw new ConflictException("Try again");
        }
    }

    /** Takes the journey's train away: 204, also for a journey that has none; 409 {@code Try again} as above. */
    @DeleteMapping("/api/admin/journeys/{id}/train")
    ResponseEntity<Void> takeAway(@PathVariable long id) {
        try {
            trains.takeAway(id);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw new ConflictException("Try again");
        }
        return ResponseEntity.noContent().build();
    }
}
