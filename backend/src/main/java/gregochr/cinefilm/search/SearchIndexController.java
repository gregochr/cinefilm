package gregochr.cinefilm.search;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The compact search index: one request, one ETag, so the browser can search without asking per keystroke. */
@RestController
public class SearchIndexController {

    private final SearchIndexCache cache;

    /** Creates the controller over the index cache. */
    public SearchIndexController(SearchIndexCache cache) {
        this.cache = cache;
    }

    @GetMapping("/api/search-index")
    ResponseEntity<byte[]> index(HttpServletRequest request) {
        SearchIndexCache.Snapshot snapshot = cache.get();
        boolean unchanged = Collections.list(request.getHeaders(HttpHeaders.IF_NONE_MATCH)).stream()
            .anyMatch(header -> matches(header, snapshot.etag()));
        HttpStatus status = unchanged ? HttpStatus.NOT_MODIFIED : HttpStatus.OK;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
            .header(HttpHeaders.ETAG, snapshot.etag())
            .cacheControl(CacheControl.noCache());
        if (unchanged) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(snapshot.body());
    }

    /** Whether an {@code If-None-Match} value (a comma list, {@code *}, quoted or not, weak or strong) names it. */
    static boolean matches(String header, String etag) {
        String wanted = opaque(etag);
        for (String candidate : header.split(",")) {
            String value = candidate.strip();
            if (value.equals("*") || opaque(value).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String opaque(String tag) {
        String value = tag.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
