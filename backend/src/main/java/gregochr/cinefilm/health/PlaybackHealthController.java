package gregochr.cinefilm.health;

import gregochr.cinefilm.health.HealthDtos.PlaybackList;
import gregochr.cinefilm.health.HealthDtos.PlaybackRow;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** The report route the player sends to, and the Admin routes that read the reports back. */
@RestController
public class PlaybackHealthController {

    private final PlaybackHealthService health;

    /** Creates the controller over the health service. */
    public PlaybackHealthController(PlaybackHealthService health) {
        this.health = health;
    }

    /**
     * Records a report. The body is read here as UTF-8 text with a hard stop at
     * {@link PlaybackHealthService#MAX_BODY_LENGTH} characters, so an oversized body is never held in memory: it
     * reaches the service as null, which refuses it. A malformed body answers {@code Invalid health report}. A failure
     * of the connection itself ({@link IOException}) is not a bad report and propagates.
     */
    @PutMapping("/api/playback-health/{playbackId}")
    ResponseEntity<Void> report(@PathVariable String playbackId, HttpServletRequest request) throws IOException {
        String body = BoundedText.read(new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8),
            PlaybackHealthService.MAX_BODY_LENGTH);
        try {
            health.report(playbackId, body);
        } catch (DataIntegrityViolationException e) {
            // Two first reports of one viewing can race (closing the tab before the first frame sends two): the loser's
            // insert fails on the unique playback id. Its transaction has rolled back and the winner's row is now
            // there, so asking once more takes the update path, where the higher seq wins. A second failure is a
            // different problem and propagates.
            health.report(playbackId, body);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/playback-health")
    PlaybackList recent() {
        return health.recent();
    }

    @GetMapping("/api/admin/playback-health/{playbackId}")
    PlaybackRow one(@PathVariable String playbackId) {
        return health.one(playbackId);
    }
}
