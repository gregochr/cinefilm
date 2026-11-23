package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.health.BoundedText;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Admin routes of journeys: import a train feed logger file, list what is stored, delete a journey. */
@RestController
public class JourneyController {

    private final JourneyImportService imports;
    private final JourneyService journeys;

    /** Creates the controller over the import service and the journey service. */
    public JourneyController(JourneyImportService imports, JourneyService journeys) {
        this.imports = imports;
        this.journeys = journeys;
    }

    /**
     * Imports one logger file. The body is read here as UTF-8 text with a hard stop at
     * {@link JourneyImportService#MAX_BODY_LENGTH} characters, so an oversized body is never held in memory: it
     * reaches the service as null, which refuses it. Two requests can clash (two posts of one file, or two files
     * with one new device): the loser's insert fails on a unique key, and is answered 409 {@code Try again}; a retry
     * gets the true answer, whichever key clashed.
     */
    @PostMapping("/api/admin/journeys")
    ResponseEntity<JourneyImportService.Result> importFile(HttpServletRequest request) throws IOException {
        String body = BoundedText.read(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8),
            JourneyImportService.MAX_BODY_LENGTH);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(imports.importFile(body));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Try again");
        }
    }

    /** Every journey, the newest run first and a run's segments in order. */
    @GetMapping("/api/admin/journeys")
    JourneyService.Listing list() {
        return journeys.list();
    }

    /**
     * Deletes the journey: 204. Answers 409 {@code Try again} when two requests clash: on the journey's foreign key
     * (a run swept while another journey is being given it) or, as a {@link PessimisticLockingFailureException},
     * when the database ends one of two requests that wait on each other. A retry gets the true answer.
     */
    @DeleteMapping("/api/admin/journeys/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        try {
            journeys.delete(id);
        } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
            throw new ConflictException("Try again");
        }
        return ResponseEntity.noContent().build();
    }
}
