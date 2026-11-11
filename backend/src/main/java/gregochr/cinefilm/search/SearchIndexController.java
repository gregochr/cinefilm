package gregochr.cinefilm.search;

import gregochr.cinefilm.web.JsonEtag;
import jakarta.servlet.http.HttpServletRequest;
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
        return JsonEtag.respond(request, snapshot.body(), snapshot.etag());
    }
}
