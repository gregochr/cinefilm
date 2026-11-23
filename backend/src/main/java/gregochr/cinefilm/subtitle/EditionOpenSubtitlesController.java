package gregochr.cinefilm.subtitle;

import gregochr.cinefilm.opensubtitles.OpenSubtitlesChargedException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesQuotaException;
import gregochr.cinefilm.opensubtitles.OpenSubtitlesUnavailableException;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadDto;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.DownloadRequest;
import gregochr.cinefilm.subtitle.EditionOpenSubtitlesDtos.SearchDto;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin: search OpenSubtitles for one edition's subtitle and download the file the owner picks. */
@RestController
public class EditionOpenSubtitlesController {

    /** The text of the 429 when OpenSubtitles' (or CineFilm's) daily download allowance is used up. */
    static final String QUOTA_MESSAGE = "OpenSubtitles daily limit reached — try again tomorrow.";
    /** The text of the 502 when OpenSubtitles charged a download but the file could not be fetched. */
    static final String CHARGED_MESSAGE =
        "OpenSubtitles counted this download against today's limit but could not deliver " + "the file. Try again.";
    /** The text of the 503 when OpenSubtitles cannot answer. */
    static final String UNAVAILABLE_MESSAGE = "OpenSubtitles is not answering. Try again in a few minutes.";

    private final EditionOpenSubtitlesService service;

    /** Creates the controller over the picker service. */
    public EditionOpenSubtitlesController(EditionOpenSubtitlesService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/editions/{id}/opensubtitles")
    SearchDto search(@PathVariable Long id, @RequestParam(required = false) String kind) {
        return service.search(id, kind);
    }

    @PostMapping("/api/admin/editions/{id}/opensubtitles/download")
    DownloadDto download(@PathVariable Long id, @RequestBody DownloadRequest request) {
        return service.download(id, request);
    }

    @ExceptionHandler(OpenSubtitlesQuotaException.class)
    ResponseEntity<Map<String, String>> quota(OpenSubtitlesQuotaException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, QUOTA_MESSAGE);
    }

    @ExceptionHandler(OpenSubtitlesChargedException.class)
    ResponseEntity<Map<String, String>> charged(OpenSubtitlesChargedException e) {
        return error(HttpStatus.BAD_GATEWAY, CHARGED_MESSAGE);
    }

    @ExceptionHandler(OpenSubtitlesUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(OpenSubtitlesUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
    }
}
