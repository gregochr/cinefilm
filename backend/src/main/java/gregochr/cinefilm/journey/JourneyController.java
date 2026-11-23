package gregochr.cinefilm.journey;

import gregochr.cinefilm.config.ConflictException;
import gregochr.cinefilm.health.BoundedText;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Admin route that imports a train feed logger file as journeys. */
@RestController
public class JourneyController {

    private final JourneyImportService imports;

    /** Creates the controller over the import service. */
    public JourneyController(JourneyImportService imports) {
        this.imports = imports;
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
}
